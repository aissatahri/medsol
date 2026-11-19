package com.medsol;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.canvas.parser.EventType;
import com.itextpdf.kernel.pdf.canvas.parser.PdfCanvasProcessor;
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor;
import com.itextpdf.kernel.pdf.canvas.parser.data.IEventData;
import com.itextpdf.kernel.pdf.canvas.parser.data.TextRenderInfo;
import com.itextpdf.kernel.pdf.canvas.parser.listener.IEventListener;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Paramètres › Impression : police, tailles, éléments masqués et positions fixes appliqués aux documents. */
class ImpressionTest extends WebTestSupport {

    private static final float MM = 72f / 25.4f;

    /** Position (x, ligne de base) en points de chaque morceau de texte de la première page. */
    private static List<Object[]> textes(byte[] pdf) throws Exception {
        List<Object[]> r = new ArrayList<>();
        try (PdfDocument d = new PdfDocument(new PdfReader(new ByteArrayInputStream(pdf)))) {
            new PdfCanvasProcessor(new IEventListener() {
                @Override public void eventOccurred(IEventData data, EventType type) {
                    if (data instanceof TextRenderInfo t) {
                        var p = t.getBaseline().getStartPoint();
                        r.add(new Object[]{t.getText(), p.get(0), p.get(1), d.getPage(1).getPageSize().getHeight()});
                    }
                }
                @Override public Set<EventType> getSupportedEvents() { return Set.of(EventType.RENDER_TEXT); }
            }).processPageContent(d.getPage(1));
        }
        return r;
    }

    private UUID consultationAvecOrdonnance() throws Exception {
        String pid = patient(doctor, "Calage");
        String loc = mvc.perform(post("/consultations/start").with(user(doctor)).with(csrf()).param("patientId", pid))
                .andReturn().getResponse().getRedirectedUrl();
        UUID c = UUID.fromString(loc.substring(loc.indexOf("c=") + 2));
        long glucor = count(doctor, "SELECT id FROM medicament WHERE nom = 'GLUCOR' AND forme = 'COMPRIME à 50 MG' AND pays = 'MA' ORDER BY id LIMIT 1");
        mvc.perform(post("/consultations/" + c + "/ordonnance").with(user(doctor)).with(csrf())
                .param("medicamentId", String.valueOf(glucor)).param("dose", "1 cp").param("duree", "1 mois"));
        return c;
    }

    @Test
    void positionsFontAndHiddenElementsAreAppliedToThePrescription() throws Exception {
        UUID c = consultationAvecOrdonnance();
        mvc.perform(get("/settings/impression").with(user(doctor))).andExpect(status().isOk())
                .andExpect(content().string(containsString("Nom du patient (cadre)")));

        // date placée à X = 100 mm, Y = 20 mm ; DCI masquée ; police Times ; nom du patient en 20 pt
        mvc.perform(post("/settings/impression").with(user(doctor)).with(csrf()).param("document", "ORDONNANCE")
                        .param("police", "TIMES").param("x_DATE", "100").param("y_DATE", "20").param("t_PATIENT", "20")
                        .param("present_DCI", "1"))
                .andExpect(flash().attribute("done", "settings.saved"));
        byte[] pdf = mvc.perform(get("/consultations/" + c + "/documents/ordonnance").with(user(doctor)))
                .andReturn().getResponse().getContentAsByteArray();

        try (PdfDocument d = new PdfDocument(new PdfReader(new ByteArrayInputStream(pdf)))) {
            assertThat(PdfTextExtractor.getTextFromPage(d.getPage(1))).contains("GLUCOR").doesNotContain("DCI :");
        }
        assertThat(new String(pdf, StandardCharsets.ISO_8859_1)).contains("Times-Roman");
        String annee = String.valueOf(java.time.LocalDate.now().getYear());
        Object[] date = textes(pdf).stream().filter(t -> ((String) t[0]).contains(annee)).findFirst().orElseThrow();
        float x = (float) date[1], base = (float) date[2], hauteur = (float) date[3];
        assertThat(x).isBetween(100 * MM - 2, 100 * MM + 40);          // la ligne commence à 100 mm (ville éventuelle avant l'année)
        assertThat(hauteur - base).isBetween(20 * MM, 20 * MM + 25);    // ligne de base juste sous Y = 20 mm

        // aperçus (données fictives) de chaque document, avec la grille de calage
        for (String doc : List.of("ORDONNANCE", "ANALYSE", "IMAGERIE", "CERTIFICAT", "LETTRE", "COMPTE_RENDU")) {
            byte[] apercu = mvc.perform(post("/settings/impression/apercu").with(user(doctor)).with(csrf()).param("document", doc).param("grille", "true")
                            .param("x_TITRE", "20").param("y_TITRE", "30").param("x_DATE", "95").param("y_DATE", "42"))
                    .andExpect(status().isOk()).andExpect(content().contentType("application/pdf")).andReturn().getResponse().getContentAsByteArray();
            String dir = System.getProperty("medsol.pdf.dir");
            if (dir != null) java.nio.file.Files.write(java.nio.file.Path.of(dir, "apercu-" + doc + ".pdf"), apercu);
        }

        // la DCI, masquée par défaut, s'active en cochant « Afficher »
        mvc.perform(post("/settings/impression").with(user(doctor)).with(csrf()).param("document", "ORDONNANCE")
                .param("present_DCI", "1").param("v_DCI", "true"));
        byte[] avecDci = mvc.perform(get("/consultations/" + c + "/documents/ordonnance").with(user(doctor)))
                .andReturn().getResponse().getContentAsByteArray();
        try (PdfDocument d = new PdfDocument(new PdfReader(new ByteArrayInputStream(avecDci)))) {
            assertThat(PdfTextExtractor.getTextFromPage(d.getPage(1))).contains("DCI : ACARBOSE");
        }

        // retour à la mise en page par défaut (DCI de nouveau masquée)
        mvc.perform(post("/settings/impression").with(user(doctor)).with(csrf()).param("document", "ORDONNANCE").param("action", "reinitialiser"))
                .andExpect(flash().attribute("done", "print.reset.done"));
        assertThat(count(doctor, "SELECT count(*) FROM document_reglage")).isZero();
        byte[] defaut = mvc.perform(get("/consultations/" + c + "/documents/ordonnance").with(user(doctor)))
                .andReturn().getResponse().getContentAsByteArray();
        try (PdfDocument d = new PdfDocument(new PdfReader(new ByteArrayInputStream(defaut)))) {
            assertThat(PdfTextExtractor.getTextFromPage(d.getPage(1))).contains("GLUCOR").doesNotContain("DCI");
        }
    }

    @Test
    void archivedRecordCanBeRestoredByTheDoctor() throws Exception {
        String pid = patient(doctor, "Archivable");
        mvc.perform(post("/patients/" + pid + "/archive").with(user(doctor)).with(csrf())).andExpect(redirectedUrl("/patients"));
        mvc.perform(get("/patients").with(user(doctor)).param("q", "Archivable"))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("ARCHIVABLE P"))));
        mvc.perform(get("/patients/archives").with(user(doctor))).andExpect(status().isOk())
                .andExpect(content().string(containsString("ARCHIVABLE P"))).andExpect(content().string(containsString("Restaurer")));
        // un autre cabinet ne peut rien restaurer ici
        mvc.perform(post("/patients/" + pid + "/restaurer").with(user(newDoctor("GENERAL"))).with(csrf()))
                .andExpect(redirectedUrl("/patients/archives"));
        assertThat(text(doctor, "SELECT archive::text FROM patient WHERE id = ?::uuid", pid)).isEqualTo("true");
        mvc.perform(post("/patients/" + pid + "/restaurer").with(user(doctor)).with(csrf())).andExpect(redirectedUrl("/patients/" + pid));
        mvc.perform(get("/patients").with(user(doctor)).param("q", "Archivable")).andExpect(content().string(containsString("Archivable")));
    }

    @Test
    void practiceAddsItsOwnHealthCoverages() throws Exception {
        mvc.perform(post("/settings/cabinet/couvertures").with(user(doctor)).with(csrf()).param("libelle", "Mutuelle OCP"))
                .andExpect(flash().attribute("done", "cov.added"));
        mvc.perform(post("/settings/cabinet/couvertures").with(user(doctor)).with(csrf()).param("libelle", "amo - cnss"))
                .andExpect(flash().attribute("error", "cov.exists"));   // déjà dans la liste commune
        mvc.perform(get("/patients/new").with(user(doctor))).andExpect(content().string(containsString("value=\"Mutuelle OCP\"")))
                .andExpect(content().string(containsString("value=\"AMO Tadamon (ex-RAMED)\"")));
        var autre = newDoctor("GENERAL");
        mvc.perform(get("/patients/new").with(user(autre))).andExpect(content().string(org.hamcrest.Matchers.not(containsString("Mutuelle OCP"))));
        long id = count(doctor, "SELECT id FROM couverture_sanitaire WHERE libelle = 'Mutuelle OCP'");
        mvc.perform(post("/settings/cabinet/couvertures/" + id + "/supprimer").with(user(autre)).with(csrf()));
        assertThat(count(doctor, "SELECT count(*) FROM couverture_sanitaire WHERE libelle = 'Mutuelle OCP'")).isEqualTo(1);   // pas chez lui
        mvc.perform(post("/settings/cabinet/couvertures/" + id + "/supprimer").with(user(doctor)).with(csrf()))
                .andExpect(flash().attribute("done", "cov.deleted"));
        assertThat(count(doctor, "SELECT count(*) FROM couverture_sanitaire WHERE libelle = 'Mutuelle OCP'")).isZero();
    }

    @Test
    void anotherPracticeDoesNotSeeTheseSettings() throws Exception {
        mvc.perform(post("/settings/impression").with(user(doctor)).with(csrf()).param("document", "CERTIFICAT").param("police", "COURIER"));
        var autre = newDoctor("GENERAL");
        assertThat(count(autre, "SELECT count(*) FROM document_reglage")).isZero();
        mvc.perform(get("/settings/impression").with(user(doctor)).param("document", "CERTIFICAT"))
                .andExpect(content().string(containsString("<option value=\"COURIER\" selected=\"selected\"")));
    }
}
