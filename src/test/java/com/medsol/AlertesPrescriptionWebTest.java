package com.medsol;

import com.medsol.dossier.ConsultationRepository;
import com.medsol.dossier.OrdonnanceRepository;
import com.medsol.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

/** Alertes dans l'écran de l'ordonnance : aperçu avant l'ajout, liste de l'ordonnance, trace au journal. */
class AlertesPrescriptionWebTest extends WebTestSupport {

    @Autowired ConsultationRepository consultations;
    @Autowired OrdonnanceRepository ordonnances;

    @Test
    void allergyAndInteractionAlertsAppearAndOverridesAreLogged() throws Exception {
        UUID patient = UUID.fromString(patient(doctor, "Allergique"));
        UUID consultation;
        long augmentin, advil, coumadine;
        TenantContext.set(doctor.cabinetId());
        try {
            jdbc.update("INSERT INTO allergie (cabinet_id, patient_id, nom) VALUES (current_cabinet(), ?, 'Pénicilline')", patient);
            consultation = consultations.duJour(patient, doctor.id(), LocalDate.now());
            augmentin = ordonnances.trouverOuCreer("AUGMENTIN TEST", "1 g", "AMOXICILLINE + ACIDE CLAVULANIQUE");
            advil = ordonnances.trouverOuCreer("ADVIL TEST", "400 mg", "IBUPROFENE");
            coumadine = ordonnances.trouverOuCreer("COUMADINE TEST", "5 mg", "WARFARINE");
        } finally {
            TenantContext.clear();
        }
        String base = "/consultations/" + consultation + "/ordonnance";

        // aperçu avant l'ajout
        mvc.perform(get(base + "/nouvelle").with(user(doctor)).param("medicamentId", Long.toString(augmentin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Contre-indication")))
                .andExpect(content().string(containsString("appartient aux pénicillines")));

        // prescrit malgré tout : affiché sur l'ordonnance et tracé
        mvc.perform(post(base).with(user(doctor)).with(csrf()).param("medicamentId", Long.toString(augmentin)).param("dose", "1 cp x 2"))
                .andExpect(content().string(containsString("Allergie connue : Pénicilline")));
        assertThat(count(doctor, "SELECT count(*) FROM journal_audit WHERE action = 'ALERTE_CONTRE_INDICATION_OUTREPASSEE'")).isEqualTo(1);

        mvc.perform(post(base).with(user(doctor)).with(csrf()).param("medicamentId", Long.toString(advil)));
        mvc.perform(post(base).with(user(doctor)).with(csrf()).param("medicamentId", Long.toString(coumadine)))
                .andExpect(content().string(containsString("Risque hémorragique majoré")));
        // pas de contre-indication pour ces deux-là : une seule trace
        assertThat(count(doctor, "SELECT count(*) FROM journal_audit WHERE action = 'ALERTE_CONTRE_INDICATION_OUTREPASSEE'")).isEqualTo(1);

        // l'onglet Ordonnances du dossier montre aussi les alertes
        mvc.perform(get("/patients/" + patient + "/dossier").with(user(doctor)).param("onglet", "ordonnances"))
                .andExpect(content().string(containsString("alertes-prescription")));

        // un patient sans allergie ni interaction : aucune alerte
        UUID autre = UUID.fromString(patient(doctor, "Tranquille"));
        UUID c2;
        TenantContext.set(doctor.cabinetId());
        try { c2 = consultations.duJour(autre, doctor.id(), LocalDate.now()); } finally { TenantContext.clear(); }
        mvc.perform(post("/consultations/" + c2 + "/ordonnance").with(user(doctor)).with(csrf()).param("medicamentId", Long.toString(advil)))
                .andExpect(content().string(not(containsString("alertes-prescription"))));
    }
}
