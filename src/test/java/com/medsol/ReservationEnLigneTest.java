package com.medsol;

import com.medsol.security.MedsolUser;
import com.medsol.security.Messagerie;
import com.medsol.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Prise de rendez-vous en ligne : page publique, demande, validation par le cabinet, annulation par le patient. */
class ReservationEnLigneTest extends WebTestSupport {

    @Autowired Messagerie messagerie;

    private String lien;

    private void configurer() throws Exception {
        TenantContext.set(doctor.cabinetId());
        try {   // ouvert tous les jours 08:00–20:00 : le test ne dépend ni du jour ni de l'heure
            for (int j = 1; j <= 7; j++) jdbc.update("INSERT INTO disponibilite (cabinet_id, jour, debut, fin) VALUES (current_cabinet(), ?, '08:00', '20:00')", j);
        } finally {
            TenantContext.clear();
        }
        lien = "test-" + doctor.cabinetId().toString().substring(0, 8);
        mvc.perform(post("/settings/reservation").with(user(doctor)).with(csrf()).param("active", "true").param("lien", lien)
                .param("duree", "30").param("delai", "0").param("horizon", "30").param("message", "Apportez vos examens."))
                .andExpect(flash().attribute("done", "settings.saved"));
    }

    private String premierCreneau() throws Exception {
        String page = mvc.perform(get("/r/" + lien)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Apportez vos examens."))).andReturn().getResponse().getContentAsString();
        Matcher m = Pattern.compile("/r/" + lien + "/creneau\\?debut=([0-9T:\\-]+)").matcher(page);
        assertThat(m.find()).as("au moins un créneau libre").isTrue();
        return m.group(1);
    }

    private MvcResult demander(String debut, String nom, String email) throws Exception {
        return mvc.perform(post("/r/" + lien).with(csrf()).param("debut", debut).param("nom", nom).param("prenom", "Salma")
                .param("telephone", "06 12 34 56 78").param("email", email).param("motif", "Contrôle").param("consentement", "true")).andReturn();
    }

    @Test
    void patientBooksOnlineAndThePracticeAccepts() throws Exception {
        configurer();
        String debut = premierCreneau();
        mvc.perform(get("/r/" + lien + "/creneau").param("debut", debut)).andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"telephone\"")));

        // demande envoyée : confirmation, e-mail avec lien d'annulation, créneau retiré de la page
        MvcResult r = demander(debut, "Idrissi", "salma@exemple.com");
        assertThat(r.getResponse().getContentAsString()).contains("Demande envoyée").contains("/r/annuler/");
        assertThat(messagerie.dernier().a()).isEqualTo("salma@exemple.com");
        assertThat(messagerie.dernier().texte()).contains("/r/annuler/");
        mvc.perform(get("/r/" + lien)).andExpect(content().string(not(containsString("debut=" + debut + "\""))));
        // le même créneau ne peut plus être demandé
        mvc.perform(post("/r/" + lien).with(csrf()).param("debut", debut).param("nom", "Autre").param("prenom", "X")
                        .param("telephone", "0611111111").param("consentement", "true"))
                .andExpect(redirectedUrl("/r/" + lien + "?du=" + debut.substring(0, 10) + "&pris"));
        // robot : champ piège rempli
        mvc.perform(post("/r/" + lien).with(csrf()).param("debut", debut).param("nom", "Bot").param("prenom", "B")
                .param("telephone", "0600000000").param("consentement", "true").param("site", "http://spam"))
                .andExpect(content().string(containsString("Demande refusée")));

        // le cabinet : pastille, liste, acceptation avec création de la fiche et du rendez-vous
        String email = "a-" + UUID.randomUUID() + "@test.com";
        mvc.perform(post("/settings/users").with(user(doctor)).with(csrf()).param("fullName", "Nadia").param("email", email)
                .param("password", "un-mot-de-passe").param("confirmation", "un-mot-de-passe"));
        MedsolUser assistante = (MedsolUser) accounts.loadUserByUsername(email);
        mvc.perform(get("/planning/demandes").with(user(assistante))).andExpect(status().isOk())
                .andExpect(content().string(containsString("IDRISSI Salma")))
                .andExpect(content().string(containsString("menu-pastille")));
        String id = text(doctor, "SELECT id::text FROM reservation WHERE nom = 'Idrissi'");
        mvc.perform(post("/planning/demandes/" + id + "/accepter").with(user(assistante)).with(csrf()))
                .andExpect(flash().attributeExists("succes")).andExpect(flash().attributeExists("whatsapp"));
        assertThat(count(doctor, "SELECT count(*) FROM patient WHERE nom = 'Idrissi' AND telephone = '06 12 34 56 78'")).isEqualTo(1);
        assertThat(count(doctor, "SELECT count(*) FROM rendez_vous r JOIN reservation x ON x.rdv_id = r.id WHERE x.id = ?::uuid AND x.statut = 'ACCEPTEE'", id)).isEqualTo(1);
        assertThat(messagerie.dernier().sujet()).contains("confirmé");

        // une seconde demande du même patient : la fiche existante est proposée ; refus
        String autre = premierCreneau();
        demander(autre, "Idrissi", "");
        mvc.perform(get("/planning/demandes").with(user(doctor))).andExpect(content().string(containsString("Accepter pour IDRISSI Salma")));
        String id2 = text(doctor, "SELECT id::text FROM reservation WHERE statut = 'EN_ATTENTE'");
        mvc.perform(post("/planning/demandes/" + id2 + "/refuser").with(user(doctor)).with(csrf())).andExpect(flash().attributeExists("succes"));
        assertThat(text(doctor, "SELECT statut FROM reservation WHERE id = ?::uuid", id2)).isEqualTo("REFUSEE");
    }

    @Test
    void patientCancelsWithTheLink() throws Exception {
        configurer();
        String debut = premierCreneau();
        String page = demander(debut, "Alaoui", "").getResponse().getContentAsString();
        Matcher m = Pattern.compile("/r/annuler/([A-Za-z0-9_-]+)").matcher(page);
        assertThat(m.find()).isTrue();
        String jeton = m.group(1);
        // acceptée, puis annulée par le patient : le rendez-vous passe « Annulé »
        String id = text(doctor, "SELECT id::text FROM reservation WHERE nom = 'Alaoui'");
        mvc.perform(post("/planning/demandes/" + id + "/accepter").with(user(doctor)).with(csrf()));
        mvc.perform(get("/r/annuler/" + jeton)).andExpect(content().string(containsString("Annuler votre rendez-vous")));
        mvc.perform(post("/r/annuler/" + jeton).with(csrf())).andExpect(content().string(containsString("Votre rendez-vous est annulé")));
        assertThat(text(doctor, "SELECT r.statut FROM rendez_vous r JOIN reservation x ON x.rdv_id = r.id WHERE x.id = ?::uuid", id)).isEqualTo("ANNULE");
        // lien déjà utilisé ; le créneau est de nouveau libre
        mvc.perform(get("/r/annuler/" + jeton)).andExpect(content().string(containsString("plus valable")));
        mvc.perform(get("/r/" + lien)).andExpect(content().string(containsString("debut=" + debut)));
    }

    @Test
    void unknownOrInactivePracticeShowsNothing() throws Exception {
        mvc.perform(get("/r/cabinet-inconnu")).andExpect(status().isNotFound())
                .andExpect(content().string(containsString("ne propose pas la prise de rendez-vous en ligne")));
        configurer();
        mvc.perform(post("/settings/reservation").with(user(doctor)).with(csrf()).param("lien", lien)
                .param("duree", "30").param("delai", "0").param("horizon", "30"));   // case décochée : désactivée
        mvc.perform(get("/r/" + lien)).andExpect(status().isNotFound());
        // réglages réservés au médecin
        mvc.perform(get("/settings/reservation").with(user(doctor))).andExpect(status().isOk());
    }
}
