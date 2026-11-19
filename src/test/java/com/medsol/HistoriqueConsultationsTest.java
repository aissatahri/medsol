package com.medsol;

import com.medsol.dossier.ConsultationRepository;
import com.medsol.dossier.OrdonnanceRepository;
import com.medsol.security.MedsolUser;
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

/** Menu « Consultations » : historique filtré par texte (patient, clinique, médicaments) et par période. */
class HistoriqueConsultationsTest extends WebTestSupport {

    @Autowired ConsultationRepository consultations;
    @Autowired OrdonnanceRepository ordonnances;

    @Test
    void historyIsSearchableByPatientClinicalMedicineAndPeriod() throws Exception {
        UUID benali = UUID.fromString(patient(doctor, "Benali"));
        UUID tazi = UUID.fromString(patient(doctor, "Tazi"));
        LocalDate jour = LocalDate.now();
        TenantContext.set(doctor.cabinetId());
        try {
            UUID c1 = consultations.duJour(benali, doctor.id(), jour);
            jdbc.update("UPDATE consultation SET motif = 'Palpitations à l''effort' WHERE id = ?", c1);
            jdbc.update("""
                    INSERT INTO consultation_champ (consultation_id, cabinet_id, type_id, valeur)
                    SELECT ?, current_cabinet(), id, 'Fibrillation atriale' FROM type_champ_clinique WHERE nom = 'DIAGNOSTIC_MEDICAL' AND cabinet_id IS NULL""", c1);
            UUID c2 = consultations.duJour(tazi, doctor.id(), jour.minusDays(40));
            ordonnances.ajouter(c2, ordonnances.trouverOuCreer("AMLOR TEST", "5 mg", "AMLODIPINE"), "1 cp", "1 mois", "");
        } finally {
            TenantContext.clear();
        }

        // sans filtre : les deux, le plus récent d'abord, motif et diagnostic affichés
        mvc.perform(get("/consultations").with(user(doctor))).andExpect(status().isOk())
                .andExpect(content().string(containsString("BENALI P")))
                .andExpect(content().string(containsString("TAZI P")))
                .andExpect(content().string(containsString("Palpitations à l")))
                .andExpect(content().string(containsString("Fibrillation atriale")));
        // par médicament (DCI)
        mvc.perform(get("/consultations").with(user(doctor)).param("q", "amlodipine").param("dans", "MEDICAMENT"))
                .andExpect(content().string(containsString("TAZI P"))).andExpect(content().string(not(containsString("BENALI P"))));
        // par diagnostic (champ clinique)
        mvc.perform(get("/consultations").with(user(doctor)).param("q", "fibrillation").param("dans", "CLINIQUE"))
                .andExpect(content().string(containsString("BENALI P"))).andExpect(content().string(not(containsString("TAZI P"))));
        // par patient
        mvc.perform(get("/consultations").with(user(doctor)).param("q", "tazi").param("dans", "PATIENT"))
                .andExpect(content().string(containsString("TAZI P"))).andExpect(content().string(not(containsString("BENALI P"))));
        // par période : les 7 derniers jours
        mvc.perform(get("/consultations").with(user(doctor)).param("du", jour.minusDays(7).toString()).param("au", jour.toString()))
                .andExpect(content().string(containsString("BENALI P"))).andExpect(content().string(not(containsString("TAZI P"))));
        // réponse partielle pour la recherche pendant la frappe
        mvc.perform(get("/consultations").with(user(doctor)).header("HX-Request", "true").param("q", "benali"))
                .andExpect(content().string(containsString("id=\"resultats\""))).andExpect(content().string(not(containsString("<html"))));

        // l'assistante n'y a pas accès ; un autre cabinet ne voit rien
        String email = "a-" + UUID.randomUUID() + "@test.com";
        mvc.perform(post("/settings/users").with(user(doctor)).with(csrf()).param("fullName", "Nadia").param("email", email)
                .param("password", "un-mot-de-passe").param("confirmation", "un-mot-de-passe"));
        MedsolUser assistante = (MedsolUser) accounts.loadUserByUsername(email);
        mvc.perform(get("/consultations").with(user(assistante))).andExpect(status().isForbidden());
        MedsolUser autre = newDoctor("GENERAL");
        mvc.perform(get("/consultations").with(user(autre)))
                .andExpect(content().string(not(containsString("BENALI P")))).andExpect(content().string(containsString("Aucune consultation ne correspond")));
    }
}
