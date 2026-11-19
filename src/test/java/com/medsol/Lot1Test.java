package com.medsol;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Lot 1 : export de la caisse, corbeille des consultations, journal d'accès. */
class Lot1Test extends WebTestSupport {

    private UUID consultationAvecActe(String pid) throws Exception {
        mvc.perform(post("/settings/actes").with(user(doctor)).with(csrf()).param("nom", "Consultation").param("prix", "300"));
        String loc = mvc.perform(post("/consultations/start").with(user(doctor)).with(csrf()).param("patientId", pid))
                .andReturn().getResponse().getRedirectedUrl();
        UUID c = UUID.fromString(loc.substring(loc.indexOf("c=") + 2));
        mvc.perform(post("/consultations/" + c + "/actes").with(user(doctor)).with(csrf())
                .param("a_" + text(doctor, "SELECT id::text FROM acte WHERE nom = 'Consultation'"), "paye"));
        return c;
    }

    @Test
    void exportBinAndAccessLog() throws Exception {
        String pid = patient(doctor, "=HYPERLINK(\"x\")");
        UUID c = consultationAvecActe(pid);
        mvc.perform(post("/caisse/" + c + "/encaisser").with(user(doctor)).with(csrf()).param("montant", "120").param("mode", "CARTE"));

        // Export pour Excel : BOM UTF-8, points-virgules, virgule décimale, formule neutralisée
        byte[] csv = mvc.perform(get("/caisse/export/consultations").with(user(doctor)))
                .andExpect(status().isOk()).andExpect(header().string("Content-Disposition", containsString(".csv")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(csv[0]).isEqualTo((byte) 0xEF);
        String texte = new String(csv, 3, csv.length - 3, StandardCharsets.UTF_8);
        assertThat(texte).contains("Total dû (DH);Versé (DH)").contains("300,00;120,00;180,00;Carte").contains("'=HYPERLINK");
        String versements = mvc.perform(get("/caisse/export/versements").with(user(doctor)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(versements).contains("TOTAL ENCAISSÉ;;;;;;;120,00");
        mvc.perform(get("/caisse/export/versements").with(user(doctor)).param("du", "2026-01-10").param("au", "2025-01-01"))
                .andExpect(status().isBadRequest());

        // Corbeille : une consultation avec actes et versement y part, puis revient à l'identique
        mvc.perform(post("/patients/" + pid + "/dossier/consultations/" + c + "/supprimer").with(user(doctor)).with(csrf()).param("motif", "doublon"))
                .andExpect(flash().attribute("succes", containsString("corbeille")));
        mvc.perform(get("/caisse").with(user(doctor))).andExpect(content().string(org.hamcrest.Matchers.not(containsString("HYPERLINK"))));
        mvc.perform(get("/consultations/corbeille").with(user(doctor)))
                .andExpect(content().string(containsString("1 acte(s) · 1 versement(s)")))
                .andExpect(content().string(containsString("Motif : doublon")));
        // une nouvelle consultation le même jour empêche le rétablissement
        String loc = mvc.perform(post("/consultations/start").with(user(doctor)).with(csrf()).param("patientId", pid))
                .andReturn().getResponse().getRedirectedUrl();
        UUID nouvelle = UUID.fromString(loc.substring(loc.indexOf("c=") + 2));
        mvc.perform(post("/consultations/corbeille/" + c + "/retablir").with(user(doctor)).with(csrf()))
                .andExpect(flash().attribute("erreur", containsString("déjà une consultation")));
        mvc.perform(post("/patients/" + pid + "/dossier/consultations/" + nouvelle + "/supprimer").with(user(doctor)).with(csrf()));
        mvc.perform(post("/consultations/corbeille/" + c + "/retablir").with(user(doctor)).with(csrf()))
                .andExpect(flash().attribute("succes", containsString("rétablie")));
        assertThat(count(doctor, "SELECT count(*) FROM consultation WHERE supprimee_le IS NULL")).isEqualTo(1);

        // Journal : lectures de dossier et modifications, filtrables
        mvc.perform(get("/settings/journal").with(user(doctor))).andExpect(status().isOk())
                .andExpect(content().string(containsString("RETABLISSEMENT")));
        mvc.perform(get("/settings/journal").with(user(doctor)).header("HX-Request", "true").param("q", "export_caisse"))
                .andExpect(content().string(containsString("EXPORT_CAISSE_CONSULTATIONS")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("RETABLISSEMENT"))));
    }
}
