package com.medsol;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Le parcours du médecin, avec le vrai catalogue marocain : allergie « Pénicilline » dans le dossier, consultation
 * ouverte, recherche « augmentin » dans l'ordonnance : l'alerte rouge s'affiche avant la prescription.
 * Puis la même page en espagnol et en anglais (pas de clé de traduction manquante).
 */
class ScenarioAugmentinTest extends WebTestSupport {

    @Test
    void penicillinAllergyRaisesARedAlertOnAugmentin() throws Exception {
        String p = patient(doctor, "Benali");

        // 1. allergie notée dans le dossier (onglet Informations)
        mvc.perform(post("/patients/" + p + "/allergies").with(user(doctor)).with(csrf()).param("nom", "Pénicilline"));

        // 2. consultation du jour ouverte
        String dossier = mvc.perform(post("/consultations/start").with(user(doctor)).with(csrf()).param("patientId", p))
                .andReturn().getResponse().getRedirectedUrl();
        Matcher c = Pattern.compile("c=([0-9a-f-]{36})").matcher(dossier);
        assertThat(c.find()).isTrue();
        String consultation = c.group(1);

        // 3. « augmentin » tapé dans la recherche de l'ordonnance : trouvé dans le catalogue du Maroc
        MvcResult recherche = mvc.perform(get("/consultations/" + consultation + "/medicaments").with(user(doctor)).param("q", "augmentin"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("AUGMENTIN"))).andReturn();
        Matcher m = Pattern.compile("medicamentId=(\\d+)").matcher(recherche.getResponse().getContentAsString());
        assertThat(m.find()).as("un Augmentin dans les résultats").isTrue();

        // 4. le médecin le choisit : alerte rouge avant même de prescrire
        mvc.perform(get("/consultations/" + consultation + "/ordonnance/nouvelle").with(user(doctor)).param("medicamentId", m.group(1)))
                .andExpect(content().string(containsString("pastille urgent")))
                .andExpect(content().string(containsString("Contre-indication")))
                .andExpect(content().string(containsString("Allergie connue : Pénicilline")))
                .andExpect(content().string(containsString("appartient aux pénicillines")));

        // les écrans du dossier dans les trois langues : aucune clé manquante (« ??clé?? »), contenu traduit
        java.util.Map<String, String[]> attendus = java.util.Map.of(
                "fr", new String[]{"Dossier médical :", "Ordonnances", "Documents médicaux"},
                "en", new String[]{"Medical record:", "Prescriptions", "Medical documents"},
                "es", new String[]{"Expediente médico:", "Recetas", "Documentos médicos"});
        for (var e : attendus.entrySet()) {
            String html = mvc.perform(get("/patients/" + p + "/dossier").with(user(doctor)).param("onglet", "ordonnances")
                            .cookie(new jakarta.servlet.http.Cookie("medsol-lang", e.getKey())))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(html).as("langue " + e.getKey()).doesNotContain("??").contains("lang=\"" + e.getKey() + "\"").contains(e.getValue());
        }
        // l'alerte rouge, en espagnol et en anglais
        mvc.perform(get("/consultations/" + consultation + "/ordonnance/nouvelle").with(user(doctor)).param("medicamentId", m.group(1))
                        .cookie(new jakarta.servlet.http.Cookie("medsol-lang", "es")))
                .andExpect(content().string(containsString("Contraindicación")))
                .andExpect(content().string(containsString("Alergia conocida: Pénicilline")))
                .andExpect(content().string(containsString("forma parte del grupo: penicilinas")));
        mvc.perform(get("/consultations/" + consultation + "/ordonnance/nouvelle").with(user(doctor)).param("medicamentId", m.group(1))
                        .cookie(new jakarta.servlet.http.Cookie("medsol-lang", "en")))
                .andExpect(content().string(containsString("Known allergy: Pénicilline")))
                .andExpect(content().string(containsString("belongs to the penicillins")));
    }
}
