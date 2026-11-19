package com.medsol;

import com.medsol.abonnement.EmailsEssai;
import com.medsol.security.MedsolUser;
import com.medsol.security.Messagerie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Croissance : bandeau du serveur de test, parrainage, offre fondateur, e-mails d'accompagnement de l'essai. */
@TestPropertySource(properties = {"medsol.environnement=test", "medsol.url-production=https://med.exemple.ma"})
class CroissanceTest extends WebTestSupport {

    @Autowired @Qualifier("plateforme") JdbcTemplate plateforme;
    @Autowired Messagerie messagerie;
    @Autowired EmailsEssai emailsEssai;

    private MedsolUser admin() {
        return new MedsolUser(doctor.id(), doctor.cabinetId(), doctor.email(), doctor.passwordHash(), doctor.fullName(),
                doctor.role(), true, null, true);
    }

    private String inscrire(String parrain) throws Exception {
        String email = "c-" + UUID.randomUUID() + "@test.com";
        var req = post("/register").with(csrf()).param("cabinetName", "Cabinet " + email).param("doctorName", "Dr Filleul")
                .param("specialty", "GENERAL").param("country", "MA").param("email", email).param("password", "un-mot-de-passe-long")
                .param("cgu", "true").param("source", "CONFRERE");
        if (parrain != null) req.param("parrain", parrain);
        mvc.perform(req).andExpect(redirectedUrl("/login?registered"));
        return email;
    }

    private UUID cabinet(String email) {
        return plateforme.queryForObject("SELECT cabinet_id FROM utilisateur WHERE email = ?", UUID.class, email);
    }

    @Test
    void testServerShowsAWarningBanner() throws Exception {
        mvc.perform(get("/login")).andExpect(content().string(containsString("Serveur de test")))
                .andExpect(content().string(containsString("https://med.exemple.ma")));
        mvc.perform(get("/patients").with(user(doctor))).andExpect(content().string(containsString("ne saisissez aucun vrai patient")));
    }

    @Test
    void referralGivesOneFreeMonthToBothOnTheFirstPayment() throws Exception {
        String code = plateforme.queryForObject("SELECT code_parrainage FROM cabinet WHERE id = ?", String.class, doctor.cabinetId());
        mvc.perform(get("/abonnement").with(user(doctor))).andExpect(content().string(containsString("/register?parrain=" + code)))
                .andExpect(content().string(containsString("Parrainez un confrère")));
        mvc.perform(get("/register").param("parrain", code.toLowerCase()))
                .andExpect(content().string(containsString("Invité par")))
                .andExpect(content().string(containsString("<option value=\"CONFRERE\" selected=\"selected\">")));

        UUID filleul = cabinet(inscrire(code));
        assertThat(plateforme.queryForObject("SELECT parrain_id FROM cabinet WHERE id = ?", UUID.class, filleul)).isEqualTo(doctor.cabinetId());
        LocalDate finParrain = plateforme.queryForObject("SELECT fin FROM abonnement WHERE cabinet_id = ?", LocalDate.class, doctor.cabinetId());

        // premier paiement du filleul : 1 mois de plus pour lui et pour le parrain ; une seule fois
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/plateforme/cabinets/" + filleul + "/paiement").with(user(admin())).with(csrf())
                    .param("periode", "MENSUEL").param("montant", "390").param("devise", "MAD").param("reference", "R" + UUID.randomUUID()));
        }
        assertThat(plateforme.queryForObject("SELECT fin FROM abonnement WHERE cabinet_id = ?", LocalDate.class, doctor.cabinetId()))
                .isEqualTo(finParrain.plusMonths(1));
        LocalDate debut = LocalDate.now();
        assertThat(plateforme.queryForObject("SELECT fin FROM abonnement WHERE cabinet_id = ?", LocalDate.class, filleul))
                .isAfterOrEqualTo(debut.plusMonths(3).minusDays(3));   // 2 mois payés + 1 offert
        mvc.perform(get("/abonnement").with(user(doctor))).andExpect(content().string(containsString("1 mois offert ✓")));
    }

    @Test
    void founderOfferWithLimitedPlaces() throws Exception {
        mvc.perform(post("/plateforme/fondateur").with(user(admin())).with(csrf())
                .param("actif", "true").param("mensuel", "290").param("annuel", "2900").param("places", "1"));
        mvc.perform(get("/")).andExpect(content().string(containsString("Offre fondateur")))
                .andExpect(content().string(containsString(">290 DH<")));
        String premier = inscrire(null);
        assertThat(plateforme.queryForObject("SELECT tarif_fondateur FROM cabinet WHERE id = ?", Boolean.class, cabinet(premier))).isTrue();
        MedsolUser fondateur = (MedsolUser) accounts.loadUserByUsername(premier);
        mvc.perform(get("/accepter-conditions").with(user(fondateur)));
        mvc.perform(get("/abonnement").with(user(fondateur))).andExpect(content().string(containsString("290,00 DH")))
                .andExpect(content().string(containsString("tarif fondateur")));
        // places épuisées : l'offre se ferme, le cabinet suivant paie le tarif normal
        String second = inscrire(null);
        assertThat(plateforme.queryForObject("SELECT tarif_fondateur FROM cabinet WHERE id = ?", Boolean.class, cabinet(second))).isFalse();
        mvc.perform(get("/")).andExpect(content().string(not(containsString("Offre fondateur"))));
        mvc.perform(post("/plateforme/fondateur").with(user(admin())).with(csrf()).param("actif", "false"));
    }

    @Test
    void trialEmailsAreSentOnceAtDays1And3And7() {
        LocalDate demain = LocalDate.now().plusDays(1);
        assertThat(emailsEssai.envoyer(demain)).isPositive();
        assertThat(plateforme.queryForObject("SELECT count(*) FROM essai_email WHERE cabinet_id = ? AND etape = 'J1'", Long.class, doctor.cabinetId()))
                .isEqualTo(1);
        emailsEssai.envoyer(demain);
        assertThat(plateforme.queryForObject("SELECT count(*) FROM essai_email WHERE cabinet_id = ?", Long.class, doctor.cabinetId())).isEqualTo(1);
        emailsEssai.envoyer(LocalDate.now().plusDays(7));
        assertThat(plateforme.queryForObject("SELECT count(*) FROM essai_email WHERE cabinet_id = ? AND etape = 'J7'", Long.class, doctor.cabinetId()))
                .isEqualTo(1);
        assertThat(messagerie.dernier().texte()).contains("register?parrain=");
    }
}
