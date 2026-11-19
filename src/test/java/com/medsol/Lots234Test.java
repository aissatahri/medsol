package com.medsol;

import com.medsol.security.MedsolUser;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Lots 2 à 4 : fichiers, courbes, fiche de soins, courriers, comptes rendus, statistiques, messagerie, rappels, paramétrage. */
class Lots234Test extends WebTestSupport {

    private UUID consultation(MedsolUser u, String pid) throws Exception {
        String loc = mvc.perform(post("/consultations/start").with(user(u)).with(csrf()).param("patientId", pid))
                .andReturn().getResponse().getRedirectedUrl();
        return UUID.fromString(loc.substring(loc.indexOf("c=") + 2));
    }

    private MedsolUser assistante() throws Exception {
        String email = "lot4-" + doctor.cabinetId() + "@test.com";
        mvc.perform(post("/settings/users").with(user(doctor)).with(csrf()).param("fullName", "Nadia").param("email", email).param("password", "un-mot-de-passe"));
        return (MedsolUser) accounts.loadUserByUsername(email);
    }

    @Test
    void attachmentsAreCheckedServedSafelyAndIsolated() throws Exception {
        String pid = patient(doctor, "Fichiers");
        MedsolUser assistante = assistante();
        MockMultipartFile pdf = new MockMultipartFile("fichier", "resultat.pdf", "application/pdf", "%PDF-1.4\n%test".getBytes());
        mvc.perform(multipart("/patients/" + pid + "/fichiers").file(pdf).param("categorie", "RESULTAT_ANALYSE").with(user(assistante)).with(csrf()))
                .andExpect(content().string(containsString("« resultat.pdf » ajouté")));
        MockMultipartFile faux = new MockMultipartFile("fichier", "virus.pdf", "application/pdf", "<script>alert(1)</script>".getBytes());
        mvc.perform(multipart("/patients/" + pid + "/fichiers").file(faux).with(user(doctor)).with(csrf()))
                .andExpect(content().string(containsString("Format refusé")));
        String f = text(doctor, "SELECT id::text FROM fichier");
        mvc.perform(get("/patients/" + pid + "/fichiers/" + f).with(user(doctor)))
                .andExpect(status().isOk()).andExpect(content().contentType("application/pdf"))
                .andExpect(header().string("Content-Security-Policy", containsString("sandbox")));
        MedsolUser autre = newDoctor("GENERAL");
        mvc.perform(get("/patients/" + pid + "/fichiers/" + f).with(user(autre))).andExpect(status().isNotFound());
        mvc.perform(get("/patients/" + pid + "/dossier").with(user(doctor)).param("onglet", "fichiers")).andExpect(status().isOk());
    }

    @Test
    void curvesCareSheetLetterAndSpecialtyReports() throws Exception {
        String pid = patient(doctor, "Courbes");
        UUID c = consultation(doctor, pid);
        long poids = count(doctor, "SELECT id FROM type_constante WHERE nom = 'POIDS' AND cabinet_id IS NULL");
        long ta = count(doctor, "SELECT id FROM type_constante WHERE nom = 'PRESSION_ARTERIELLE' AND cabinet_id IS NULL");
        long diag = count(doctor, "SELECT id FROM type_champ_clinique WHERE nom = 'DIAGNOSTIC_MEDICAL' AND cabinet_id IS NULL");
        mvc.perform(post("/consultations/" + c).with(user(doctor)).with(csrf())
                .param("s_" + poids, "82").param("x_" + ta + "_BRAS_GAUCHE", "14/9").param("c_" + diag, "HTA essentielle"));

        mvc.perform(get("/patients/" + pid + "/dossier/evolution").with(user(doctor)))
                .andExpect(content().string(containsString("Poids")))
                .andExpect(content().string(containsString("140")))      // 14/9 cm Hg → 140/90 mm Hg
                .andExpect(content().string(containsString("<svg")));
        mvc.perform(get("/patients/" + pid + "/dossier/fiche-soins").with(user(doctor)))
                .andExpect(status().isOk()).andExpect(content().contentType("application/pdf"));

        // Lettre : préremplie depuis la consultation ; comptes rendus de la cardiologie (ETT…)
        mvc.perform(get("/consultations/" + c + "/redaction/LETTRE").with(user(doctor)))
                .andExpect(content().string(containsString("HTA essentielle")));
        mvc.perform(get("/consultations/" + c + "/redaction/ETT").with(user(doctor)))
                .andExpect(content().string(containsString("FEVG")));
        mvc.perform(post("/consultations/" + c + "/redaction/ETT").with(user(doctor)).with(csrf())
                        .param("contenu", "FEVG 60 %").param("conclusion", "ETT normale").param("imprimer", "true"))
                .andExpect(status().isOk()).andExpect(content().contentType("application/pdf"));
        assertThat(text(doctor, "SELECT conclusion FROM redaction WHERE modele = 'ETT'")).isEqualTo("ETT normale");
        mvc.perform(get("/patients/" + pid + "/dossier").with(user(doctor)).param("onglet", "ordonnances"))
                .andExpect(content().string(containsString("Compte rendu ETT")));
        // l'ETT n'existe pas pour un médecin généraliste
        MedsolUser generaliste = newDoctor("GENERAL");
        UUID c2 = consultation(generaliste, patient(generaliste, "Gen"));
        mvc.perform(get("/consultations/" + c2 + "/redaction/ETT").with(user(generaliste))).andExpect(status().isNotFound());

        mvc.perform(get("/statistiques").with(user(doctor))).andExpect(status().isOk())
                .andExpect(content().string(containsString("Activité mois par mois")));
    }

    @Test
    void chatRemindersAndConsultationSettings() throws Exception {
        MedsolUser assistante = assistante();
        String pid = patient(doctor, "Rappel");
        String patientId = pid;
        mvc.perform(post("/chat/messages").with(user(assistante)).with(csrf()).param("texte", "Patient arrivé [[patient:" + patientId + "|RAPPEL P]]"))
                .andExpect(jsonPath("$.miens").value(true));
        mvc.perform(get("/chat/messages").with(user(doctor)))
                .andExpect(jsonPath("$.nonLus").value(1)).andExpect(jsonPath("$.messages[0].nom").value("Nadia"))
                .andExpect(jsonPath("$.messages[0].miens").value(false));
        mvc.perform(post("/chat/lus").with(user(doctor)).with(csrf()));
        mvc.perform(get("/chat/messages").with(user(doctor))).andExpect(jsonPath("$.nonLus").value(0));
        MedsolUser autre = newDoctor("GENERAL");
        mvc.perform(get("/chat/messages").with(user(autre))).andExpect(jsonPath("$.messages.length()").value(0));

        // Rappels de demain : lien WhatsApp au format international du pays (Maroc : 212)
        mvc.perform(post("/patients/" + pid).with(user(doctor)).with(csrf()).param("nom", "Rappel").param("prenom", "P").param("telephone", "06 12 34 56 78"));
        String demain = java.time.LocalDate.now(java.time.ZoneId.of("Africa/Casablanca")).plusDays(1).toString();
        mvc.perform(post("/planning").with(user(assistante)).with(csrf()).param("forcer", "true")
                .param("patientId", pid).param("date", demain).param("heure", "10:00").param("duree", "30"));
        mvc.perform(get("/planning/rappels").with(user(assistante)))
                .andExpect(content().string(containsString("https://wa.me/212612345678?text=Bonjour%20P")));
        String rdv = text(doctor, "SELECT id::text FROM rendez_vous");
        mvc.perform(post("/planning/rappels/" + rdv).with(user(assistante)).with(csrf()).param("canal", "WHATSAPP").param("jour", demain))
                .andExpect(flash().attribute("succes", "Rappel noté."));
        mvc.perform(get("/planning/rappels").with(user(assistante))).andExpect(content().string(containsString("Rappelé : WHATSAPP")));

        // Paramètres › Consultation : masquer un modèle (pour ce cabinet seulement), ajouter un champ propre
        long ett = count(doctor, "SELECT id FROM type_champ_clinique WHERE nom = 'ETT'");
        mvc.perform(post("/settings/consultation/CHAMP/" + ett + "/masque").with(user(doctor)).with(csrf()).param("masque", "true"));
        mvc.perform(post("/settings/consultation/CHAMP").with(user(doctor)).with(csrf()).param("libelle", "Examen vasculaire"));
        mvc.perform(post("/settings/consultation/CONSTANTE").with(user(doctor)).with(csrf()).param("libelle", "Tour de taille").param("unite", "cm"));
        UUID c = consultation(doctor, pid);
        mvc.perform(get("/patients/" + pid + "/dossier").with(user(doctor)).param("onglet", "consultation").param("c", c.toString()))
                .andExpect(content().string(not(containsString("ETT / Échocardiographie"))))
                .andExpect(content().string(containsString("Examen vasculaire")))
                .andExpect(content().string(containsString("Tour de taille")));
        MedsolUser autreCardio = newDoctor("CARDIO");
        UUID c3 = consultation(autreCardio, patient(autreCardio, "Autre"));
        mvc.perform(get("/patients/" + text(autreCardio, "SELECT patient_id::text FROM consultation") + "/dossier").with(user(autreCardio))
                        .param("onglet", "consultation").param("c", c3.toString()))
                .andExpect(content().string(containsString("ETT / Échocardiographie")))
                .andExpect(content().string(not(containsString("Examen vasculaire"))));
    }
}
