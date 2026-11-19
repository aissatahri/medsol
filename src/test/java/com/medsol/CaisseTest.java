package com.medsol;

import com.medsol.security.MedsolUser;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Caisse et actes repris de SGCM-Cardio. */
class CaisseTest extends WebTestSupport {

    private MedsolUser assistante(MedsolUser medecin) throws Exception {
        String email = "caisse-" + medecin.cabinetId() + "@test.com";
        mvc.perform(post("/settings/users").with(user(medecin)).with(csrf())
                .param("fullName", "Assistante").param("email", email).param("password", "un-mot-de-passe"));
        return (MedsolUser) accounts.loadUserByUsername(email);
    }

    private UUID consultation(String pid) throws Exception {
        String loc = mvc.perform(post("/consultations/start").with(user(doctor)).with(csrf()).param("patientId", pid))
                .andReturn().getResponse().getRedirectedUrl();
        return UUID.fromString(loc.substring(loc.indexOf("c=") + 2));
    }

    private String acte(String nom) { return text(doctor, "SELECT id::text FROM acte WHERE nom = ?", nom); }

    @Test
    void actsPaymentsInvoiceReceiptAndClosing() throws Exception {
        // Catalogue (Paramètres › Actes)
        mvc.perform(post("/settings/actes").with(user(doctor)).with(csrf()).param("nom", "Consultation").param("prix", "300"))
                .andExpect(flash().attribute("done", "settings.saved"));
        mvc.perform(post("/settings/actes").with(user(doctor)).with(csrf()).param("nom", "ECG").param("prix", "200,50"));
        mvc.perform(post("/settings/actes").with(user(doctor)).with(csrf()).param("nom", "consultation").param("prix", "1"))
                .andExpect(flash().attribute("error", "actes.exists"));

        String pid = patient(doctor, "Caisse");
        UUID c = consultation(pid);
        // Actes : consultation payée, ECG offert → total 300
        mvc.perform(post("/consultations/" + c + "/actes").with(user(doctor)).with(csrf())
                        .param("a_" + acte("Consultation"), "paye").param("a_" + acte("ECG"), "offert"))
                .andExpect(content().string(containsString("Actes enregistrés.")))
                .andExpect(content().string(containsString("Total 300,00 DH")));

        // Nouveau tarif : la consultation passée garde son prix
        mvc.perform(post("/settings/actes/" + acte("Consultation")).with(user(doctor)).with(csrf()).param("prix", "350").param("actif", "true"));
        assertThat(new BigDecimal(text(doctor, "SELECT sum(prix)::text FROM consultation_acte WHERE paye"))).isEqualByComparingTo("300");

        // L'assistante encaisse : versement partiel, trop-perçu refusé, solde
        MedsolUser assistante = assistante(doctor);
        mvc.perform(get("/caisse").with(user(assistante))).andExpect(status().isOk())
                .andExpect(content().string(containsString("CAISSE P")))
                .andExpect(content().string(containsString("À payer")));
        mvc.perform(post("/caisse/" + c + "/encaisser").with(user(assistante)).with(csrf()).param("montant", "100").param("mode", "ESPECES"))
                .andExpect(flash().attribute("succes", "100,00 DH encaissés (Espèces)."));
        mvc.perform(post("/caisse/" + c + "/encaisser").with(user(assistante)).with(csrf()).param("montant", "250").param("mode", "CARTE"))
                .andExpect(flash().attribute("erreur", containsString("dépasse le reste à payer (200,00 DH)")));
        mvc.perform(get("/caisse").with(user(assistante)).param("impayes", "true"))
                .andExpect(content().string(containsString("Partiel")));
        mvc.perform(post("/caisse/" + c + "/encaisser").with(user(assistante)).with(csrf()).param("montant", "200").param("mode", "CARTE"));
        mvc.perform(get("/caisse").with(user(assistante)).param("impayes", "true"))
                .andExpect(content().string(not(containsString("CAISSE P"))));

        // Annulation d'un versement : gardée en base, retirée des totaux
        String v = text(doctor, "SELECT id::text FROM versement WHERE montant = 200");
        mvc.perform(post("/caisse/" + c + "/versements/" + v + "/annuler").with(user(assistante)).with(csrf()))
                .andExpect(flash().attribute("succes", "Versement annulé."));
        assertThat(count(doctor, "SELECT count(*) FROM versement")).isEqualTo(2);
        assertThat(count(doctor, "SELECT count(*) FROM versement WHERE annule_le IS NULL")).isEqualTo(1);

        // Reçu, facture numérotée (enregistrée, numéro jamais redonné), clôture du jour
        mvc.perform(get("/caisse/" + c + "/recu").with(user(assistante)))
                .andExpect(status().isOk()).andExpect(content().contentType("application/pdf"));
        mvc.perform(get("/caisse/" + c + "/facture").with(user(assistante)))
                .andExpect(redirectedUrl("/patients/" + pid + "/facture?consultation=" + c));
        mvc.perform(get("/patients/" + pid + "/facture").with(user(assistante)).param("consultation", c.toString()))
                .andExpect(status().isOk()).andExpect(content().string(containsString("checked")));
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/patients/" + pid + "/facture").with(user(assistante)).with(csrf())
                            .param("date", "2026-11-15").param("acte_" + acte("Consultation"), "1").param("prix_" + acte("Consultation"), "300"))
                    .andExpect(status().isOk()).andExpect(content().contentType("application/pdf"))
                    .andExpect(header().string("Content-Disposition", containsString("15112026-00" + (i + 1))));
        }
        assertThat(count(doctor, "SELECT count(DISTINCT numero) FROM facture")).isEqualTo(2);
        mvc.perform(post("/patients/" + pid + "/facture").with(user(assistante)).with(csrf()).param("date", "2026-11-15"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/caisse/cloture").with(user(assistante)))
                .andExpect(status().isOk()).andExpect(content().contentType("application/pdf"));

        // Onglet Paiement du dossier (médecin) ; l'assistante n'enregistre pas les actes
        mvc.perform(get("/patients/" + pid + "/dossier").with(user(doctor)).param("onglet", "paiement"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("200,00 DH")));
        mvc.perform(post("/consultations/" + c + "/actes").with(user(assistante)).with(csrf())).andExpect(status().isForbidden());

        // Autre cabinet : rien de visible, rien d'encaissable
        MedsolUser autre = newDoctor("GENERAL");
        mvc.perform(post("/caisse/" + c + "/encaisser").with(user(autre)).with(csrf()).param("montant", "10").param("mode", "ESPECES"))
                .andExpect(flash().attribute("erreur", "Cette consultation n'existe plus."));
        mvc.perform(get("/caisse/" + c + "/recu").with(user(autre))).andExpect(status().isNotFound());
    }

    @Test
    void amountsInWordsFollowTheCabinetCurrency() {
        assertThat(com.medsol.caisse.Monnaie.enLettres(new BigDecimal("1250.50"), "MAD"))
                .isEqualTo("Mille deux cent cinquante dirhams et cinquante centimes");
        assertThat(com.medsol.caisse.Monnaie.enLettres(new BigDecimal("71"), "EUR")).isEqualTo("Soixante-et-onze euros");
        assertThat(com.medsol.caisse.Monnaie.enLettres(new BigDecimal("72"), "MAD")).isEqualTo("Soixante-douze dirhams");
        assertThat(com.medsol.caisse.Monnaie.enLettres(new BigDecimal("70"), "MAD")).isEqualTo("Soixante-dix dirhams");
        assertThat(com.medsol.caisse.Monnaie.enLettres(new BigDecimal("80"), "MAD")).isEqualTo("Quatre-vingts dirhams");
        assertThat(com.medsol.caisse.Monnaie.enLettres(new BigDecimal("91"), "MAD")).isEqualTo("Quatre-vingt-onze dirhams");
        assertThat(com.medsol.caisse.Monnaie.enLettres(new BigDecimal("99.75"), "MAD"))
                .isEqualTo("Quatre-vingt-dix-neuf dirhams et soixante-quinze centimes");
        assertThat(com.medsol.caisse.Monnaie.enLettres(new BigDecimal("1"), "MAD")).isEqualTo("Un dirham");
        assertThat(com.medsol.caisse.Monnaie.enLettres(new BigDecimal("15000"), "XOF")).isEqualTo("Quinze mille francs CFA");
        assertThat(com.medsol.caisse.Monnaie.format(new BigDecimal("15000"), "XOF")).isEqualTo("15 000 FCFA");
    }
}
