package com.medsol;

import com.medsol.abonnement.AbonnementService;
import com.medsol.abonnement.StripeService;
import com.medsol.security.MedsolUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** SaaS : essai, lecture seule à l'échéance, espace administrateur, paiements manuels et Stripe. */
class AbonnementTest extends WebTestSupport {

    @Autowired @Qualifier("plateforme") JdbcTemplate plateforme;
    @Autowired AbonnementService abonnements;

    private MedsolUser admin() {
        return new MedsolUser(doctor.id(), doctor.cabinetId(), doctor.email(), doctor.passwordHash(), doctor.fullName(),
                doctor.role(), true, null, true);
    }

    @Test
    void newPracticeStartsA30DayTrialThenBecomesReadOnly() throws Exception {
        assertThat(text(doctor, "SELECT statut FROM abonnement")).isEqualTo("ESSAI");
        assertThat(text(doctor, "SELECT (fin - current_date)::text FROM abonnement")).isEqualTo("30");
        mvc.perform(get("/abonnement").with(user(doctor))).andExpect(status().isOk())
                .andExpect(content().string(containsString("Essai gratuit")))
                .andExpect(content().string(containsString("390,00 DH")));

        // Échéance passée : tout reste lisible, rien ne s'écrit
        plateforme.update("UPDATE abonnement SET fin = current_date - 1 WHERE cabinet_id = ?", doctor.cabinetId());
        mvc.perform(get("/patients").with(user(doctor))).andExpect(status().isOk())
                .andExpect(content().string(containsString("lecture seule")));
        mvc.perform(post("/patients").with(user(doctor)).with(csrf()).param("nom", "Bloque").param("prenom", "X"))
                .andExpect(redirectedUrl("/abonnement?lecture"));
        mvc.perform(post("/queue").with(user(doctor)).with(csrf()).header("HX-Request", "true").param("patientId", java.util.UUID.randomUUID().toString()))
                .andExpect(header().string("HX-Redirect", "/abonnement?lecture"));
        assertThat(count(doctor, "SELECT count(*) FROM patient WHERE nom = 'Bloque'")).isZero();
        mvc.perform(post("/logout").with(user(doctor)).with(csrf())).andExpect(redirectedUrl("/login?logout"));
    }

    @Test
    void platformAdminRecordsManualPaymentsAndManagesTrials() throws Exception {
        mvc.perform(get("/plateforme").with(user(doctor))).andExpect(status().isForbidden());
        mvc.perform(get("/plateforme").with(user(admin()))).andExpect(status().isOk())
                .andExpect(content().string(containsString(doctor.email())));

        plateforme.update("UPDATE abonnement SET fin = current_date - 5 WHERE cabinet_id = ?", doctor.cabinetId());
        mvc.perform(post("/plateforme/cabinets/" + doctor.cabinetId() + "/paiement").with(user(admin())).with(csrf())
                        .param("periode", "ANNUEL").param("montant", "3900").param("devise", "MAD").param("reference", "VIR-001"))
                .andExpect(flash().attribute("succes", containsString("abonnement actif jusqu'au " + LocalDate.now().plusYears(1).minusDays(1))));
        assertThat(text(doctor, "SELECT statut FROM abonnement")).isEqualTo("ACTIF");
        // même référence : refusée (pas de double prolongation)
        mvc.perform(post("/plateforme/cabinets/" + doctor.cabinetId() + "/paiement").with(user(admin())).with(csrf())
                        .param("periode", "ANNUEL").param("montant", "3900").param("devise", "MAD").param("reference", "VIR-001"))
                .andExpect(flash().attribute("erreur", containsString("déjà enregistrée")));
        mvc.perform(get("/abonnement").with(user(doctor))).andExpect(content().string(containsString("3 900,00 DH")));

        mvc.perform(post("/plateforme/cabinets/" + doctor.cabinetId() + "/suspension").with(user(admin())).with(csrf()).param("suspendu", "true"));
        mvc.perform(post("/patients").with(user(doctor)).with(csrf()).param("nom", "A").param("prenom", "B"))
                .andExpect(redirectedUrl("/abonnement?lecture"));
        mvc.perform(post("/plateforme/cabinets/" + doctor.cabinetId() + "/suspension").with(user(admin())).with(csrf()).param("suspendu", "false"));
        assertThat(text(doctor, "SELECT statut FROM abonnement")).isEqualTo("ACTIF");

        // le cabinet ne peut pas se prolonger lui-même (aucun droit d'écriture)
        com.medsol.tenant.TenantContext.set(doctor.cabinetId());
        try {
            assertThatThrownBy(() -> jdbc.update("UPDATE abonnement SET fin = '2099-01-01'"))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        } finally { com.medsol.tenant.TenantContext.clear(); }
    }

    @Test
    void stripeCallbackIsVerifiedAndAppliedOnce() throws Exception {
        StripeService stripe = new StripeService("sk_test", "whsec_test");
        String charge = """
                {"type":"checkout.session.completed","data":{"object":{"id":"cs_test_1","payment_status":"paid",
                 "client_reference_id":"%s","amount_total":3900,"currency":"eur","metadata":{"periode":"MENSUEL"}}}}"""
                .formatted(doctor.cabinetId());
        long t = Instant.now().getEpochSecond();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("whsec_test".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = "t=" + t + ",v1=" + HexFormat.of().formatHex(mac.doFinal((t + "." + charge).getBytes(StandardCharsets.UTF_8)));

        var paiement = stripe.rappel(charge, signature, Instant.now()).orElseThrow();
        assertThat(paiement.montant()).isEqualByComparingTo(new BigDecimal("39.00"));
        assertThat(paiement.devise()).isEqualTo("EUR");
        assertThatThrownBy(() -> stripe.rappel(charge.replace("3900", "1"), signature, Instant.now())).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> stripe.rappel(charge, signature, Instant.now().plusSeconds(3600))).isInstanceOf(SecurityException.class);

        assertThat(abonnements.prolonger(paiement.cabinetId(), paiement.periode(), paiement.montant(), paiement.devise(), "STRIPE",
                paiement.reference(), "Stripe", LocalDate.now())).isPresent();
        assertThat(abonnements.prolonger(paiement.cabinetId(), paiement.periode(), paiement.montant(), paiement.devise(), "STRIPE",
                paiement.reference(), "Stripe", LocalDate.now())).isEmpty();   // rappel rejoué : une seule prolongation

        // le point d'entrée public refuse un rappel non signé (et n'exige pas de jeton CSRF)
        mvc.perform(post("/stripe/webhook").content(charge).contentType("application/json")).andExpect(status().isBadRequest());
    }
}
