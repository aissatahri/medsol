package com.medsol;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor;
import com.medsol.abonnement.RappelsAbonnement;
import com.medsol.appointment.RappelsAutomatiques;
import com.medsol.security.Messagerie;
import com.medsol.security.MedsolUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Exploitation commerciale : conditions générales, éditeur et factures d'abonnement, rappels automatiques. */
class CommercialTest extends WebTestSupport {

    @Autowired @Qualifier("plateforme") JdbcTemplate plateforme;
    @Autowired Messagerie messagerie;
    @Autowired RappelsAbonnement rappelsAbonnement;
    @Autowired RappelsAutomatiques rappelsRdv;

    private MedsolUser admin() {
        return new MedsolUser(doctor.id(), doctor.cabinetId(), doctor.email(), doctor.passwordHash(), doctor.fullName(),
                doctor.role(), true, null, true);
    }

    private static String texte(byte[] pdf) throws Exception {
        try (PdfDocument d = new PdfDocument(new PdfReader(new ByteArrayInputStream(pdf)))) {
            return PdfTextExtractor.getTextFromPage(d.getPage(1));
        }
    }

    @Test
    void registrationRequiresAcceptingTheTermsAndRecordsTheVersion() throws Exception {
        mvc.perform(get("/conditions")).andExpect(status().isOk()).andExpect(content().string(containsString("loi n° 09-08")));
        String email = "cgu-" + UUID.randomUUID() + "@test.com";
        var inscription = post("/register").with(csrf()).param("cabinetName", "Cabinet CGU").param("doctorName", "Nadia Alami")
                .param("specialty", "GENERAL").param("country", "MA").param("currency", "MAD").param("timezone", "Africa/Casablanca")
                .param("email", email).param("password", "un-mot-de-passe-long").param("source", "CONFRERE").param("sourceDetail", "Dr Alami");
        mvc.perform(inscription).andExpect(status().isOk()).andExpect(content().string(containsString("accepter les conditions générales")));
        // la liste propose les nouvelles spécialités, plus « Dentaire »
        mvc.perform(get("/register")).andExpect(content().string(containsString("Endocrinologie - Diabétologie")))
                .andExpect(content().string(containsString("Médecine du travail")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("value=\"DENTAIRE\""))));
        mvc.perform(post("/register").with(csrf()).param("cabinetName", "Cabinet Dents").param("doctorName", "X")
                        .param("specialty", "DENTAIRE").param("country", "MA").param("currency", "MAD").param("timezone", "Africa/Casablanca")
                        .param("email", "dents-" + UUID.randomUUID() + "@test.com").param("password", "un-mot-de-passe-long").param("cgu", "true").param("source", "GOOGLE"))
                .andExpect(status().isOk());   // refusée : formulaire réaffiché
        mvc.perform(inscription.param("cgu", "true")).andExpect(redirectedUrl("/login?registered"));
        assertThat(plateforme.queryForObject("""
                SELECT c.cgu_version || ' ' || (c.cgu_acceptees_le IS NOT NULL) FROM cabinet c JOIN utilisateur u ON u.cabinet_id = c.id
                WHERE u.email = ?""", String.class, email)).isEqualTo(com.medsol.cabinet.RegistrationService.CGU_VERSION + " true");
        assertThat(plateforme.queryForObject("""
                SELECT c.dpa_version FROM cabinet c JOIN utilisateur u ON u.cabinet_id = c.id WHERE u.email = ?""", String.class, email))
                .isEqualTo(com.medsol.cabinet.RegistrationService.CGU_VERSION);
    }

    @Autowired com.medsol.cabinet.RegistrationService registration;

    @Test
    void presentationSheetWithQrCodeAndEditableTariff() throws Exception {
        mvc.perform(post("/plateforme/editeur").with(user(admin())).with(csrf())
                .param("raison_sociale", "Aissa Tahri").param("telephone", "06 12 34 56 78").param("email", "contact@exemple.ma"));
        mvc.perform(post("/plateforme/tarifs").with(user(admin())).with(csrf()).param("formule", "CABINET").param("devise", "mad")
                        .param("mensuel", "290").param("annuel", "2900"))
                .andExpect(flash().attribute("succes", "Tarif MAD enregistré : 290 par mois, 2900 par an."));
        mvc.perform(get("/plateforme").with(user(admin()))).andExpect(content().string(containsString("2 mois offerts")))
                .andExpect(content().string(containsString("Ouvrir la fiche (PDF)")));
        byte[] fiche = mvc.perform(get("/plateforme/fiche-presentation").with(user(admin())))
                .andExpect(status().isOk()).andExpect(content().contentType("application/pdf")).andReturn().getResponse().getContentAsByteArray();
        String dir = System.getProperty("medsol.pdf.dir");
        if (dir != null) java.nio.file.Files.write(java.nio.file.Path.of(dir, "fiche-presentation.pdf"), fiche);
        try (PdfDocument d = new PdfDocument(new PdfReader(new ByteArrayInputStream(fiche)))) {
            assertThat(d.getNumberOfPages()).isEqualTo(1);
            assertThat(PdfTextExtractor.getTextFromPage(d.getPage(1)))
                    .contains("30 jours d'essai gratuit", "290 DH", "2 900 DH", "Aissa Tahri", "06 12 34 56 78", "Scannez pour créer votre cabinet");
        }
        mvc.perform(get("/plateforme/fiche-presentation").with(user(doctor))).andExpect(status().isForbidden());
        // le QR code mène à l'inscription avec le canal « Visite » présélectionné
        mvc.perform(get("/register").param("source", "VISITE"))
                .andExpect(content().string(containsString("<option value=\"VISITE\" selected=\"selected\">")));
        plateforme.update("UPDATE formule_prix SET mensuel = 390, annuel = 3900 WHERE formule_code = 'CABINET' AND devise = 'MAD'");
    }

    @Test
    void publicHomePageAndAcquisitionChannel() throws Exception {
        mvc.perform(post("/plateforme/editeur").with(user(admin())).with(csrf()).param("telephone", "06 12 34 56 78"));
        mvc.perform(get("/")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Votre cabinet médical, simple et bien organisé.")))
                .andExpect(content().string(containsString(">390 DH<")))
                .andExpect(content().string(containsString("https://wa.me/212612345678?text=")));
        mvc.perform(get("/").with(user(doctor))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Votre cabinet médical, simple"))));
        // la question est obligatoire à l'inscription ; le canal apparaît dans l'espace Plateforme
        String email = "canal-" + UUID.randomUUID() + "@test.com";
        var inscription = post("/register").with(csrf()).param("cabinetName", "Cabinet Canal").param("doctorName", "Nadia")
                .param("specialty", "GENERAL").param("country", "MA").param("email", email)
                .param("password", "un-mot-de-passe-long").param("cgu", "true");
        mvc.perform(inscription).andExpect(status().isOk()).andExpect(content().string(containsString("Indiquez comment vous avez connu MedSol")));
        mvc.perform(inscription.param("source", "DELEGUE").param("sourceDetail", "Laboratoire X")).andExpect(redirectedUrl("/login?registered"));
        assertThat(plateforme.queryForObject("""
                SELECT c.source || ' ' || c.source_detail FROM cabinet c JOIN utilisateur u ON u.cabinet_id = c.id WHERE u.email = ?""",
                String.class, email)).isEqualTo("DELEGUE Laboratoire X");
        mvc.perform(get("/plateforme").with(user(admin()))).andExpect(content().string(containsString("Comment les cabinets ont connu MedSol")))
                .andExpect(content().string(containsString("Délégué médical")));
    }

    @Test
    void existingPracticeMustAcceptTheDataProcessingAgreement() throws Exception {
        for (String page : new String[]{"/sous-traitance", "/confidentialite", "/mentions-legales"}) {
            mvc.perform(get(page)).andExpect(status().isOk()).andExpect(content().string(containsString("Mentions légales")));
        }
        mvc.perform(get("/sous-traitance")).andExpect(content().string(containsString("Hetzner Online GmbH")))
                .andExpect(content().string(containsString("72 heures")));
        // cabinet inscrit avant l'accord : le médecin est conduit à la page d'acceptation
        String email = "ancien-" + UUID.randomUUID() + "@test.com";
        com.medsol.cabinet.RegistrationForm f = new com.medsol.cabinet.RegistrationForm();
        f.setCabinetName("Ancien cabinet"); f.setDoctorName("Ancien"); f.setEmail(email); f.setPassword("motdepasse-long");
        f.setCountry("MA"); f.setSpecialty("GENERAL");
        registration.register(f, "fr");
        MedsolUser ancien = (MedsolUser) accounts.loadUserByUsername(email);
        mvc.perform(get("/patients").with(user(ancien))).andExpect(redirectedUrl("/accepter-conditions"));
        mvc.perform(get("/accepter-conditions").with(user(ancien))).andExpect(status().isOk())
                .andExpect(content().string(containsString("Accepter et continuer")));
        mvc.perform(post("/accepter-conditions").with(user(ancien)).with(csrf())).andExpect(status().isOk())
                .andExpect(content().string(containsString("Cochez la case")));
        mvc.perform(post("/accepter-conditions").with(user(ancien)).with(csrf()).param("accepte", "true")).andExpect(redirectedUrl("/"));
        assertThat(plateforme.queryForObject("SELECT dpa_version || ' ' || (dpa_accepte_par = ?) FROM cabinet WHERE id = ?", String.class,
                ancien.id(), ancien.cabinetId())).isEqualTo(com.medsol.cabinet.RegistrationService.CGU_VERSION + " true");
        mvc.perform(get("/patients").with(user(ancien))).andExpect(status().isOk());
    }

    @Autowired com.medsol.abonnement.ComptesPlateforme comptesPlateforme;

    @Test
    void platformAdministratorIsNotACustomerPractice() throws Exception {
        MedsolUser chef = newDoctor("AUTRE");
        comptesPlateforme.marquer(chef.email());
        assertThat(plateforme.queryForObject("SELECT interne FROM cabinet WHERE id = ?", Boolean.class, chef.cabinetId())).isTrue();
        assertThat(plateforme.queryForObject("SELECT statut || ' ' || fin FROM abonnement WHERE cabinet_id = ?", String.class, chef.cabinetId()))
                .isEqualTo("ACTIF 2099-12-31");
        MedsolUser admin = new MedsolUser(chef.id(), chef.cabinetId(), chef.email(), chef.passwordHash(), chef.fullName(),
                chef.role(), true, null, true);
        // accueil = espace Plateforme ; son cabinet n'apparaît pas parmi les clients ; menu sans les pages du cabinet
        mvc.perform(get("/").with(user(admin))).andExpect(redirectedUrl("/plateforme"));
        mvc.perform(get("/plateforme").with(user(admin))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString(chef.email()))))
                .andExpect(content().string(containsString("Administrateur MedSol")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("href=\"/patients\""))));
        assertThat(rappelsAbonnement.envoyer(LocalDate.parse("2099-12-24"))).isZero();
    }

    @Test
    void registrationDerivesCurrencyAndTimeZoneFromTheCountry() throws Exception {
        String email = "pays-" + UUID.randomUUID() + "@test.com";
        mvc.perform(post("/register").with(csrf()).param("cabinetName", "Cabinet Dakar").param("doctorName", "Awa Diop")
                        .param("specialty", "PEDIATRIE").param("country", "SN").param("email", email)
                        .param("password", "un-mot-de-passe-long").param("cgu", "true").param("source", "GOOGLE"))
                .andExpect(redirectedUrl("/login?registered"));
        assertThat(plateforme.queryForObject("""
                SELECT c.devise || ' ' || c.fuseau FROM cabinet c JOIN utilisateur u ON u.cabinet_id = c.id WHERE u.email = ?""",
                String.class, email)).isEqualTo("XOF Africa/Dakar");
        mvc.perform(get("/register")).andExpect(content().string(containsString("Démarrer mon essai gratuit")))
                .andExpect(content().string(containsString("<option value=\"MA\" selected=\"selected\">Maroc</option>")));
    }

    @Test
    void newSpecialtiesHaveTheirOwnConsultationFields() throws Exception {
        MedsolUser endocrino = newDoctor("ENDOCRINO");
        String pid = patient(endocrino, "Diabete");
        mvc.perform(post("/consultations/start").with(user(endocrino)).with(csrf()).param("patientId", pid));
        mvc.perform(get("/patients/" + pid + "/dossier").with(user(endocrino)).param("onglet", "consultation"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Examen des pieds")))
                .andExpect(content().string(containsString("HbA1c")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("ETT / Échocardiographie"))));
    }

    @Test
    void publisherDetailsInstructionsAndNumberedSubscriptionInvoice() throws Exception {
        mvc.perform(post("/plateforme/editeur").with(user(admin())).with(csrf())
                .param("raison_sociale", "Aissa Tahri").param("statut_juridique", "Auto-entrepreneur")
                .param("ice", "003456789000012").param("identifiant_fiscal", "52345678").param("mention_tva", "TVA non applicable")
                .param("instructions_paiement", "Virement au RIB 011 780 0000000000000000 00"));
        mvc.perform(get("/abonnement").with(user(doctor))).andExpect(content().string(containsString("RIB 011 780")));

        mvc.perform(post("/plateforme/cabinets/" + doctor.cabinetId() + "/paiement").with(user(admin())).with(csrf())
                .param("periode", "ANNUEL").param("montant", "3900").param("devise", "MAD").param("reference", "VIR-" + UUID.randomUUID()));
        String numero = text(doctor, "SELECT numero FROM paiement_abonnement");
        assertThat(numero).matches("MS-" + LocalDate.now().getYear() + "-\\d{4}");
        String id = text(doctor, "SELECT id::text FROM paiement_abonnement");
        byte[] pdf = mvc.perform(get("/abonnement/paiements/" + id + "/facture").with(user(doctor)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(texte(pdf)).contains("Aissa Tahri", "Auto-entrepreneur", "ICE : 003456789000012", "IF : 52345678",
                "FACTURE", numero, "Abonnement MedSol Cabinet", "3 900,00", "Trois mille neuf cents dirhams", "TVA non applicable");
        // un autre cabinet ne voit pas cette facture
        mvc.perform(get("/abonnement/paiements/" + id + "/facture").with(user(newDoctor("GENERAL")))).andExpect(status().isNotFound());
    }

    @Test
    void subscriptionRemindersAreSentOncePerDeadline() {
        LocalDate aujourdhui = LocalDate.now();
        plateforme.update("UPDATE abonnement SET fin = ? WHERE cabinet_id = ?", aujourdhui.plusDays(7), doctor.cabinetId());
        assertThat(rappelsAbonnement.envoyer(aujourdhui)).isPositive();
        assertThat(messagerie.dernier().sujet()).contains("7 jours");
        assertThat(plateforme.queryForObject("SELECT count(*) FROM abonnement_rappel WHERE cabinet_id = ?", Long.class, doctor.cabinetId())).isEqualTo(1);
        rappelsAbonnement.envoyer(aujourdhui);   // relancé le même jour : rien de plus pour ce cabinet
        assertThat(plateforme.queryForObject("SELECT count(*) FROM abonnement_rappel WHERE cabinet_id = ?", Long.class, doctor.cabinetId())).isEqualTo(1);
    }

    @Test
    void automaticAppointmentRemindersByEmailWithoutTheReason() throws Exception {
        String pid = patient(doctor, "Rappel");
        plateforme.update("UPDATE patient SET email = 'salma@example.com', prenom = 'Salma' WHERE id = ?::uuid", pid);
        plateforme.update("""
                INSERT INTO rendez_vous (cabinet_id, patient_id, debut, fin, motif)
                VALUES (?, ?::uuid, now() + interval '20 hours', now() + interval '20 hours 20 minutes', 'Douleur thoracique')""",
                doctor.cabinetId(), pid);
        assertThat(rappelsRdv.envoyer()).isZero();   // réglage désactivé par défaut

        mvc.perform(post("/settings/rappels").with(user(doctor)).with(csrf()).param("email", "true").param("heures", "24"))
                .andExpect(flash().attribute("done", "settings.saved"));
        mvc.perform(get("/settings/rappels").with(user(doctor))).andExpect(status().isOk())
                .andExpect(content().string(containsString("Rappel par e-mail")));
        assertThat(rappelsRdv.envoyer()).isGreaterThanOrEqualTo(1);
        Messagerie.Mail m = messagerie.dernier();
        assertThat(m.a()).isEqualTo("salma@example.com");
        assertThat(m.texte()).contains("Bonjour Salma", "à ").doesNotContain("Douleur");
        assertThat(count(doctor, "SELECT count(*) FROM rdv_rappel WHERE canal = 'EMAIL_AUTO'")).isEqualTo(1);
        rappelsRdv.envoyer();   // une seule fois
        assertThat(count(doctor, "SELECT count(*) FROM rdv_rappel WHERE canal = 'EMAIL_AUTO'")).isEqualTo(1);
    }
}
