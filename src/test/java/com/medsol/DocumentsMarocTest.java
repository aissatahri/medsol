package com.medsol;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Documents conformes au Maroc (en français) : en-tête du médecin, INPE, « Fait à », DCI, mentions fiscales. */
class DocumentsMarocTest extends WebTestSupport {

    /** -Dmedsol.pdf.dir=… : garde les PDF produits pour les regarder. */
    private static void garder(String nom, byte[] pdf) throws Exception {
        String dir = System.getProperty("medsol.pdf.dir");
        if (dir != null) java.nio.file.Files.write(java.nio.file.Path.of(dir, nom + ".pdf"), pdf);
    }

    private static String texte(byte[] pdf) throws Exception {
        try (PdfDocument d = new PdfDocument(new PdfReader(new ByteArrayInputStream(pdf)))) {
            StringBuilder b = new StringBuilder();
            for (int i = 1; i <= d.getNumberOfPages(); i++) b.append(PdfTextExtractor.getTextFromPage(d.getPage(i))).append('\n');
            return b.toString();
        }
    }

    private byte[] pdf(String url, String... params) throws Exception {
        var req = get(url).with(user(doctor));
        for (int i = 0; i < params.length; i += 2) req.param(params[i], params[i + 1]);
        byte[] b = mvc.perform(req).andExpect(status().isOk()).andExpect(content().contentType("application/pdf"))
                .andReturn().getResponse().getContentAsByteArray();
        garder(url.substring(url.lastIndexOf('/') + 1) + "-" + System.nanoTime(), b);
        return b;
    }

    private void reglages(String ice, boolean papierEntete) throws Exception {
        mvc.perform(post("/settings/cabinet").with(user(doctor)).with(csrf())
                .param("nom", "Cabinet Test").param("adresse", "12 boulevard Mohammed V").param("ville", "Oujda")
                .param("telephone", "0536 00 00 01").param("numeroOrdre", "ORD-4521").param("identifiant", "123456789")
                .param("identifiantFiscal", "40112233").param("ice", ice).param("taxeProfessionnelle", "25123456")
                .param("papierEntete", String.valueOf(papierEntete)));
    }

    @Test
    void preprintedPaperByDefaultLeavesHeaderAndFooterEmpty() throws Exception {
        assertThat(text(doctor, "SELECT papier_entete::text FROM cabinet")).isEqualTo("true");
        String pid = patient(doctor, "Preimprime");
        String loc = mvc.perform(post("/consultations/start").with(user(doctor)).with(csrf()).param("patientId", pid))
                .andReturn().getResponse().getRedirectedUrl();
        UUID c = UUID.fromString(loc.substring(loc.indexOf("c=") + 2));
        long glucor = count(doctor, "SELECT id FROM medicament WHERE nom = 'GLUCOR' AND forme = 'COMPRIME à 50 MG' AND pays = 'MA' ORDER BY id LIMIT 1");
        mvc.perform(post("/consultations/" + c + "/ordonnance").with(user(doctor)).with(csrf())
                .param("medicamentId", String.valueOf(glucor)).param("dose", "1 cp").param("duree", "1 mois"));
        assertThat(texte(pdf("/consultations/" + c + "/documents/ordonnance")))
                .contains("GLUCOR").doesNotContain("INPE", "Inscrit à l'Ordre", "Signature et cachet");
        assertThat(texte(pdf("/consultations/" + c + "/documents/analyse"))).doesNotContain("INPE", "Inscrit à l'Ordre");
    }

    @Test
    void prescriptionCertificateAndInvoiceCarryMoroccanMentions() throws Exception {
        assertThat(text(doctor, "SELECT pays FROM cabinet")).isEqualTo("MA");
        // ICE incorrect : refusé
        mvc.perform(post("/settings/cabinet").with(user(doctor)).with(csrf()).param("nom", "Cabinet Test").param("ice", "1234"))
                .andExpect(flash().attribute("error", "settings.ice.invalid"));
        reglages("001 234 567 000 089", false);
        assertThat(text(doctor, "SELECT ice FROM cabinet")).isEqualTo("001234567000089");

        String pid = patient(doctor, "Maroc");
        String loc = mvc.perform(post("/consultations/start").with(user(doctor)).with(csrf()).param("patientId", pid))
                .andReturn().getResponse().getRedirectedUrl();
        UUID c = UUID.fromString(loc.substring(loc.indexOf("c=") + 2));
        long glucor = count(doctor, "SELECT id FROM medicament WHERE nom = 'GLUCOR' AND forme = 'COMPRIME à 50 MG' AND pays = 'MA' ORDER BY id LIMIT 1");
        mvc.perform(post("/consultations/" + c + "/ordonnance").with(user(doctor)).with(csrf())
                .param("medicamentId", String.valueOf(glucor)).param("dose", "1 cp matin et soir").param("duree", "3 mois"));

        String ordonnance = texte(pdf("/consultations/" + c + "/documents/ordonnance"));
        assertThat(ordonnance).contains("Dr ", "Inscrit à l'Ordre n° ORD-4521", "INPE : 123456789", "Cabinet Test",
                "Tél. : 0536 00 00 01", "Oujda, le ", "GLUCOR", "Signature et cachet du médecin")
                .doesNotContain("DCI");   // composant du médicament : masqué par défaut (activable dans Paramètres › Impression)

        String certificat = texte(pdf("/consultations/" + c + "/documents/certificat", "type", "consultation"));
        assertThat(certificat).contains("INPE : 123456789", "Fait à Oujda, le ");

        // Facture : identifiants fiscaux, INPE, exonération de TVA, montant en lettres
        mvc.perform(post("/settings/actes").with(user(doctor)).with(csrf()).param("nom", "Consultation").param("prix", "300"));
        String acte = text(doctor, "SELECT id::text FROM acte WHERE nom = 'Consultation'");
        byte[] facture = mvc.perform(post("/patients/" + pid + "/facture").with(user(doctor)).with(csrf())
                        .param("date", "2026-11-15").param("acte_" + acte, "1").param("prix_" + acte, "300"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        garder("facture", facture);
        assertThat(texte(facture)).contains("IF : 40112233", "ICE : 001234567000089", "TP : 25123456", "INPE : 123456789",
                "Inscrit à l'Ordre n° ORD-4521", "exonérées de TVA", "Arrêté la somme de");

        // Papier à en-tête : MedSol n'imprime plus l'en-tête du médecin
        reglages("001234567000089", true);
        assertThat(texte(pdf("/consultations/" + c + "/documents/ordonnance")))
                .doesNotContain("INPE", "DCI").contains("Oujda, le ", "GLUCOR");
    }
}
