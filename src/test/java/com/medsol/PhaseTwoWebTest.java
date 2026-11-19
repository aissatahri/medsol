package com.medsol;

import com.medsol.cabinet.RegistrationForm;
import com.medsol.cabinet.RegistrationService;
import com.medsol.security.AccountService;
import com.medsol.security.MedsolUser;
import com.medsol.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Rôles, comptes assistante, accueil du médecin (salle d'attente). */
class PhaseTwoWebTest extends WebTestSupport {

    @Test
    void assistantCreatedByDoctorHasNoAccessToMedicalRecord() throws Exception {
        String pid = patient(doctor, "Dossier");
        mvc.perform(post("/settings/users").with(user(doctor)).with(csrf())
                        .param("fullName", "Assistante").param("email", "assist-" + doctor.cabinetId() + "@test.com").param("password", "un-mot-de-passe"))
                .andExpect(flash().attribute("done", "users.created"));
        MedsolUser assistant = (MedsolUser) accounts.loadUserByUsername("assist-" + doctor.cabinetId() + "@test.com");
        assertThat(assistant.role()).isEqualTo("SECRETAIRE");
        assertThat(assistant.cabinetId()).isEqualTo(doctor.cabinetId());

        for (String url : new String[]{"/patients", "/queue", "/planning", "/patients/" + pid}) {
            mvc.perform(get(url).with(user(assistant))).andExpect(status().isOk());
        }
        mvc.perform(get("/patients/" + pid + "/dossier").with(user(assistant))).andExpect(status().isForbidden());
        mvc.perform(post("/consultations/start").with(user(assistant)).with(csrf()).param("patientId", pid))
                .andExpect(status().isForbidden());
        mvc.perform(post("/patients/" + pid + "/allergies").with(user(assistant)).with(csrf()).param("nom", "x"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/settings/users").with(user(assistant))).andExpect(status().isForbidden());
    }

    @Test
    void doctorHomeListsWaitingPatientsAndOpensTheirConsultation() throws Exception {
        String pid = patient(doctor, "Attente");
        mvc.perform(post("/queue/add").with(user(doctor)).with(csrf()).param("patientId", pid).param("motif", "tension"));
        mvc.perform(get("/").with(user(doctor)))
                .andExpect(content().string(not(containsString("href=\"/queue\""))))
                .andExpect(content().string(containsString("from-queue")));

        TenantContext.set(doctor.cabinetId());
        UUID entry;
        try { entry = jdbc.queryForObject("SELECT id FROM file_attente", UUID.class); } finally { TenantContext.clear(); }
        mvc.perform(post("/consultations/from-queue/" + entry).with(user(doctor)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/patients/" + pid + "/dossier?onglet=consultation&c=*"));
        assertThat(count(doctor, "SELECT count(*) FROM file_attente WHERE statut = 'EN_CONSULTATION'")).isEqualTo(1);
        assertThat(count(doctor, "SELECT count(*) FROM consultation")).isEqualTo(1);

        String pid2 = patient(doctor, "Second");
        mvc.perform(post("/queue/add").with(user(doctor)).with(csrf()).param("patientId", pid2));
        TenantContext.set(doctor.cabinetId());
        UUID entry2;
        try { entry2 = jdbc.queryForObject("SELECT id FROM file_attente WHERE patient_id = ?::uuid", UUID.class, pid2); }
        finally { TenantContext.clear(); }
        mvc.perform(post("/consultations/from-queue/" + entry2).with(user(doctor)).with(csrf()))
                .andExpect(redirectedUrl("/")).andExpect(flash().attribute("error", "queue.busy"));
    }

    @Test
    void usersScreenShowsAccountsAndChecksPasswordConfirmation() throws Exception {
        mvc.perform(post("/settings/users").with(user(doctor)).with(csrf()).param("fullName", "Salma").param("email", "s-" + doctor.cabinetId() + "@test.com")
                        .param("password", "un-mot-de-passe").param("confirmation", "autre-mot-de-passe"))
                .andExpect(flash().attribute("error", "users.password.mismatch"));
        mvc.perform(post("/settings/users").with(user(doctor)).with(csrf()).param("fullName", "Salma").param("email", "s-" + doctor.cabinetId() + "@test.com")
                        .param("password", "un-mot-de-passe").param("confirmation", "un-mot-de-passe"))
                .andExpect(flash().attribute("done", "users.created"));
        mvc.perform(get("/settings/users").with(user(doctor))).andExpect(status().isOk())
                .andExpect(content().string(containsString("Salma")))
                .andExpect(content().string(containsString("(vous)")))
                .andExpect(content().string(containsString("Dernière connexion")))
                .andExpect(content().string(containsString("parametres-menu")));
        for (String r : new String[]{"cabinet", "horaires", "actes", "consultation", "journal"}) {
            mvc.perform(get("/settings/" + r).with(user(doctor))).andExpect(status().isOk())
                    .andExpect(content().string(containsString("parametres-menu")));
        }
    }

    @Test
    void settingsAndPasswordRules() throws Exception {
        mvc.perform(get("/settings/cabinet").with(user(doctor))).andExpect(status().isOk());
        mvc.perform(get("/settings/users").with(user(doctor))).andExpect(status().isOk());
        mvc.perform(post("/settings/users").with(user(doctor)).with(csrf())
                        .param("fullName", "A").param("email", "short@phase2.com").param("password", "court"))
                .andExpect(flash().attribute("error", "users.password.short"));
        mvc.perform(post("/settings/users").with(user(doctor)).with(csrf())
                        .param("fullName", "A").param("email", doctor.email()).param("password", "un-mot-de-passe"))
                .andExpect(flash().attribute("error", "register.email.taken"));
        mvc.perform(post("/settings/users/" + doctor.id() + "/active").with(user(doctor)).with(csrf()).param("value", "false"))
                .andExpect(flash().attribute("error", "users.self"));
    }

    @Test
    void globalSearchFindsPatientsOfTheCabinetOnly() throws Exception {
        patient(doctor, "Recherchable");
        mvc.perform(get("/recherche").with(user(doctor)).param("q", "recherch"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("RECHERCHABLE")));
        MedsolUser other = newDoctor("GENERAL");
        mvc.perform(get("/recherche").with(user(other)).param("q", "recherch"))
                .andExpect(content().string(not(containsString("RECHERCHABLE"))));
    }
}
