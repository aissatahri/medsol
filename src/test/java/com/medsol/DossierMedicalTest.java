package com.medsol;

import com.medsol.security.MedsolUser;
import com.medsol.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Dossier médical repris de SGCM-Cardio : consultation, ordonnance, examens, documents PDF. */
class DossierMedicalTest extends WebTestSupport {

    private UUID consultation(MedsolUser u, String pid) throws Exception {
        String loc = mvc.perform(post("/consultations/start").with(user(u)).with(csrf()).param("patientId", pid))
                .andReturn().getResponse().getRedirectedUrl();
        return UUID.fromString(loc.substring(loc.indexOf("c=") + 2));
    }

    private long typeId(MedsolUser u, String table, String nom) {
        return count(u, "SELECT id FROM " + table + " WHERE nom = ? AND cabinet_id IS NULL", nom);
    }

    private long medicament(MedsolUser u, String nom, String forme) {
        return count(u, "SELECT id FROM medicament WHERE nom = ? AND forme = ? AND pays = 'MA' ORDER BY id LIMIT 1", nom, forme);
    }

    @Test
    void consultationTabUsesSpecialtyFieldsAndComputesBmi() throws Exception {
        String pid = patient(doctor, "Cardio");
        UUID c = consultation(doctor, pid);
        assertThat(consultation(doctor, pid)).isEqualTo(c);   // une seule consultation par jour

        mvc.perform(get("/patients/" + pid + "/dossier").with(user(doctor)).param("onglet", "consultation"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("ETT / Échocardiographie")))   // champ de la cardiologie
                .andExpect(content().string(containsString("AT Bras G")));                // constante complexe

        long poids = typeId(doctor, "type_constante", "POIDS");
        long taille = typeId(doctor, "type_constante", "TAILLE");
        long imc = typeId(doctor, "type_constante", "IMC");
        long ta = typeId(doctor, "type_constante", "PRESSION_ARTERIELLE");
        long symptomes = typeId(doctor, "type_champ_clinique", "SYMPTOMES");
        mvc.perform(post("/consultations/" + c).with(user(doctor)).with(csrf()).header("HX-Request", "true")
                        .param("s_" + poids, "80").param("s_" + taille, "1,75").param("s_" + imc, "999")
                        .param("x_" + ta + "_BRAS_GAUCHE", "130/80").param("c_" + symptomes, "Douleur thoracique"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Consultation enregistrée.")))
                .andExpect(content().string(containsString("26.12")))       // IMC calculé, pas la valeur saisie
                .andExpect(content().string(containsString("130/80")));
        assertThat(count(doctor, "SELECT count(*) FROM consultation WHERE motif = 'Douleur thoracique'")).isEqualTo(1);

        MedsolUser ophtalmo = newDoctor("OPHTALMO");
        String p2 = patient(ophtalmo, "Yeux");
        consultation(ophtalmo, p2);
        mvc.perform(get("/patients/" + p2 + "/dossier").with(user(ophtalmo)).param("onglet", "consultation"))
                .andExpect(content().string(containsString("Acuité visuelle")))
                .andExpect(content().string(not(containsString("ETT / Échocardiographie"))));
    }

    @Test
    void prescriptionFromCatalogueWithAllergyWarningTemplatesAndPreviousOrder() throws Exception {
        String pid = patient(doctor, "Ordonnance");
        mvc.perform(post("/patients/" + pid + "/allergies").with(user(doctor)).with(csrf()).param("nom", "Acarbose"));
        // consultation d'hier, puis celle du jour
        String loc = mvc.perform(post("/patients/" + pid + "/dossier/consultations").with(user(doctor)).with(csrf())
                .param("date", "2026-01-10")).andReturn().getResponse().getRedirectedUrl();
        UUID hier = UUID.fromString(loc.substring(loc.indexOf("c=") + 2));

        mvc.perform(get("/consultations/" + hier + "/medicaments").with(user(doctor)).param("q", "glucor 50"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("ACARBOSE")));
        long glucor = medicament(doctor, "GLUCOR", "COMPRIME à 50 MG");
        mvc.perform(get("/consultations/" + hier + "/ordonnance/nouvelle").with(user(doctor)).param("medicamentId", String.valueOf(glucor)))
                .andExpect(content().string(containsString("Allergie connue : Acarbose")));
        mvc.perform(post("/consultations/" + hier + "/ordonnance").with(user(doctor)).with(csrf())
                        .param("medicamentId", String.valueOf(glucor)).param("dose", "1 cp matin et soir").param("duree", "3 mois"))
                .andExpect(content().string(containsString("1 cp matin et soir - 3 mois")));

        // ordonnance type
        mvc.perform(post("/consultations/" + hier + "/ordonnance/modeles").with(user(doctor)).with(csrf()).param("nom", "Diabète"))
                .andExpect(content().string(containsString("Ordonnance type « Diabète » enregistrée")));
        mvc.perform(post("/consultations/" + hier + "/ordonnance/modeles").with(user(doctor)).with(csrf()).param("nom", "diabète"))
                .andExpect(content().string(containsString("existe déjà")));

        // reprise de l'ordonnance précédente dans la consultation du jour
        UUID jour = consultation(doctor, pid);
        mvc.perform(get("/patients/" + pid + "/dossier").with(user(doctor)).param("onglet", "ordonnances").param("c", jour.toString()))
                .andExpect(content().string(containsString("Reprendre l’ordonnance précédente")));
        mvc.perform(post("/consultations/" + jour + "/ordonnance/reprendre").with(user(doctor)).with(csrf()))
                .andExpect(content().string(containsString("1 ligne(s) reprise(s)")));
        // appliquer le modèle : le médicament est déjà prescrit, rien n'est ajouté
        UUID modele = UUID.fromString(text(doctor, "SELECT id::text FROM ordonnance_type"));
        UUID ligneModele = UUID.fromString(text(doctor, "SELECT id::text FROM ordonnance_type_ligne"));
        mvc.perform(post("/consultations/" + jour + "/ordonnance/modeles/" + modele + "/appliquer").with(user(doctor)).with(csrf())
                        .param("lignes", ligneModele.toString()))
                .andExpect(content().string(containsString("ces médicaments sont déjà sur l")));

        // médicament absent du catalogue : ajouté au catalogue du cabinet seulement
        mvc.perform(post("/consultations/" + jour + "/ordonnance/catalogue").with(user(doctor)).with(csrf())
                        .param("nom", "Remede Maison").param("forme", "sirop").param("dose", "1 c. à soupe"))
                .andExpect(content().string(containsString("Remede Maison")));
        MedsolUser autre = newDoctor("GENERAL");
        UUID c2 = consultation(autre, patient(autre, "Autre"));
        mvc.perform(get("/consultations/" + c2 + "/medicaments").with(user(autre)).param("q", "remede maison"))
                .andExpect(content().string(not(containsString("sirop"))));

        // PDF de l'ordonnance (mise en page du bureau)
        MvcResult pdf = mvc.perform(get("/consultations/" + jour + "/documents/ordonnance").with(user(doctor)))
                .andExpect(status().isOk()).andExpect(content().contentType("application/pdf"))
                .andExpect(header().string("Cache-Control", containsString("no-store"))).andReturn();
        assertThat(new String(pdf.getResponse().getContentAsByteArray(), 0, 5)).isEqualTo("%PDF-");
    }

    @Test
    void examsFromCatalogueWithResultAndRequestPdf() throws Exception {
        String pid = patient(doctor, "Analyses");
        UUID c = consultation(doctor, pid);
        mvc.perform(get("/consultations/" + c + "/examens/analyse/types").with(user(doctor)).param("q", "NFS"))
                .andExpect(content().string(containsString("Numération Formule Sanguine")));
        long nfs = count(doctor, "SELECT id FROM type_examen WHERE code->>'fr' = 'NFS'");
        mvc.perform(post("/consultations/" + c + "/examens/analyse").with(user(doctor)).with(csrf())
                        .param("typeId", String.valueOf(nfs)).param("description", "à jeun"))
                .andExpect(content().string(containsString("Numération Formule Sanguine")));
        UUID e = UUID.fromString(text(doctor, "SELECT id::text FROM examen"));
        mvc.perform(post("/consultations/" + c + "/examens/analyse/" + e).with(user(doctor)).with(csrf())
                        .param("resultat", "Hb 13 g/dl").param("description", "à jeun"))
                .andExpect(content().string(containsString("Résultat enregistré.")));
        mvc.perform(get("/consultations/" + c + "/documents/analyse").with(user(doctor)))
                .andExpect(status().isOk()).andExpect(content().contentType("application/pdf"));
        mvc.perform(get("/patients/" + pid + "/dossier").with(user(doctor)).param("onglet", "imageries"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Aucun examen d’imagerie demandé.")));
        mvc.perform(get("/consultations/" + c + "/documents/certificat").with(user(doctor))
                        .param("type", "arret-travail").param("debut", "2026-11-14").param("fin", "2026-11-16"))
                .andExpect(status().isOk()).andExpect(content().contentType("application/pdf"));
        mvc.perform(get("/consultations/" + c + "/documents/certificat").with(user(doctor))
                        .param("type", "arret-travail").param("debut", "2026-11-16").param("fin", "2026-11-14"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void dossierOfAnotherCabinetIsNotReachable() throws Exception {
        String pid = patient(doctor, "Prive");
        UUID c = consultation(doctor, pid);
        MedsolUser other = newDoctor("GENERAL");
        mvc.perform(get("/patients/" + pid + "/dossier").with(user(other))).andExpect(status().isNotFound());
        mvc.perform(get("/consultations/" + c + "/documents/ordonnance").with(user(other))).andExpect(status().isNotFound());
        mvc.perform(get("/consultations/" + c + "/ordonnance").with(user(other))).andExpect(status().isNotFound());
    }

    @Test
    void deletingAConsultationWithContentMovesItToTheBin() throws Exception {
        String pid = patient(doctor, "Corbeille");
        UUID vide = consultation(doctor, pid);
        mvc.perform(post("/patients/" + pid + "/dossier/consultations/" + vide + "/supprimer").with(user(doctor)).with(csrf()))
                .andExpect(flash().attribute("succes", "Consultation vide supprimée."));
        UUID pleine = consultation(doctor, pid);
        long symptomes = typeId(doctor, "type_champ_clinique", "SYMPTOMES");
        mvc.perform(post("/consultations/" + pleine).with(user(doctor)).with(csrf()).param("c_" + symptomes, "Toux"));
        mvc.perform(post("/patients/" + pid + "/dossier/consultations/" + pleine + "/supprimer").with(user(doctor)).with(csrf())
                        .param("motif", "erreur de patient"))
                .andExpect(flash().attribute("succes", containsString("corbeille")));
        assertThat(count(doctor, "SELECT count(*) FROM consultation WHERE supprimee_le IS NOT NULL")).isEqualTo(1);
    }

}
