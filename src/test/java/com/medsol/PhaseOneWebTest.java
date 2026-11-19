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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
class PhaseOneWebTest extends AbstractDbTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired WebApplicationContext ctx;
    @Autowired RegistrationService registration;
    @Autowired AccountService accounts;
    @Autowired JdbcTemplate jdbc;

    MockMvc mvc;
    MedsolUser doctor;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        doctor = newDoctor();
    }

    private MedsolUser newDoctor() {
        String email = "dr" + SEQ.incrementAndGet() + "@test.com";
        RegistrationForm f = new RegistrationForm();
        f.setCabinetName("Cabinet " + email); f.setDoctorName("Dr Test"); f.setEmail(email);
        f.setPassword("motdepasse-long"); f.setCountry("MA"); f.setSpecialty("GENERAL");
        f.setTimezone("Africa/Casablanca"); f.setCurrency("MAD"); f.setCgu(true);
        registration.register(f, "fr");
        return (MedsolUser) accounts.loadUserByUsername(email);
    }

    private String createPatient(MedsolUser u, String nom, String prenom) throws Exception {
        String location = mvc.perform(post("/patients").with(user(u)).with(csrf())
                        .param("nom", nom).param("prenom", prenom).param("dateNaissance", "1980-05-17")
                        .param("telephone", "0600000000").param("consentement", "true"))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        return location.substring(location.lastIndexOf('/') + 1);
    }

    private long count(MedsolUser u, String sql, Object... args) {
        TenantContext.set(u.cabinetId());
        try { return jdbc.queryForObject(sql, Long.class, args); } finally { TenantContext.clear(); }
    }

    @Test
    void pagesRender() throws Exception {
        for (String url : new String[]{"/", "/patients", "/patients/new", "/queue", "/planning"}) {
            mvc.perform(get(url).with(user(doctor))).andExpect(status().isOk());
        }
    }

    @Test
    void anonymousIsRedirectedToLogin() throws Exception {
        mvc.perform(get("/patients")).andExpect(status().is3xxRedirection());
    }

    @Test
    void securityHeaders() throws Exception {
        mvc.perform(get("/patients").with(user(doctor)))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().exists("Content-Security-Policy"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }

    @Test
    void postWithoutCsrfIsRejected() throws Exception {
        mvc.perform(post("/patients").with(user(doctor)).param("nom", "A").param("prenom", "B"))
                .andExpect(status().isForbidden());
    }

    @Test
    void patientCrudSearchDuplicateAndAudit() throws Exception {
        String id = createPatient(doctor, "Alaoui", "Sara");

        mvc.perform(get("/patients").with(user(doctor)).param("q", "alao"))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("ALAOUI")));

        // htmx : fragment seul, sans la mise en page
        mvc.perform(get("/patients").with(user(doctor)).param("q", "sara").header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<html"))))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("ALAOUI")));

        // doublon refusé, sauf confirmation explicite
        mvc.perform(post("/patients").with(user(doctor)).with(csrf())
                        .param("nom", "alaoui").param("prenom", "SARA").param("dateNaissance", "1980-05-17"))
                .andExpect(status().isOk()).andExpect(view().name("patients/form"));

        mvc.perform(post("/patients/" + id).with(user(doctor)).with(csrf())
                        .param("nom", "Alaoui").param("prenom", "Sarah"))
                .andExpect(status().is3xxRedirection());

        mvc.perform(get("/patients/" + id).with(user(doctor)))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Sarah")));

        assertThat(count(doctor, "SELECT count(*) FROM journal_audit WHERE objet = 'patient' AND objet_id = ?", id))
                .isGreaterThanOrEqualTo(3);   // création, modification, lecture
    }

    @Test
    void patientFromAnotherCabinetIsNotFound() throws Exception {
        String id = createPatient(doctor, "Secret", "Patient");
        MedsolUser other = newDoctor();
        mvc.perform(get("/patients/" + id).with(user(other))).andExpect(status().isNotFound());
        mvc.perform(get("/patients").with(user(other)).param("q", "secret"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Secret"))));
    }

    @Test
    void invalidPatientIsRejected() throws Exception {
        mvc.perform(post("/patients").with(user(doctor)).with(csrf())
                        .param("nom", "").param("prenom", "X").param("telephone", "<script>"))
                .andExpect(status().isOk()).andExpect(view().name("patients/form"));
    }

    @Test
    void waitingRoomRules() throws Exception {
        String p1 = createPatient(doctor, "Un", "Patient");
        String p2 = createPatient(doctor, "Deux", "Patient");

        mvc.perform(post("/queue/add").with(user(doctor)).with(csrf()).param("patientId", p1)).andExpect(status().is3xxRedirection());
        mvc.perform(post("/queue/add").with(user(doctor)).with(csrf()).param("patientId", p2)).andExpect(status().is3xxRedirection());
        // doublon : refusé
        mvc.perform(post("/queue/add").with(user(doctor)).with(csrf()).param("patientId", p1))
                .andExpect(flash().attributeExists("error"));
        assertThat(count(doctor, "SELECT count(*) FROM file_attente")).isEqualTo(2);

        UUID e1 = firstEntry(doctor, p1), e2 = firstEntry(doctor, p2);
        mvc.perform(post("/queue/" + e1 + "/statut").with(user(doctor)).with(csrf())
                        .param("attendu", "EN_ATTENTE").param("nouveau", "EN_CONSULTATION"))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("En consultation")));
        // un seul patient en consultation à la fois
        mvc.perform(post("/queue/" + e2 + "/statut").with(user(doctor)).with(csrf())
                        .param("attendu", "EN_ATTENTE").param("nouveau", "EN_CONSULTATION"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("déjà en consultation")));
        // action périmée (un autre poste l'a déjà fait)
        mvc.perform(post("/queue/" + e1 + "/statut").with(user(doctor)).with(csrf())
                        .param("attendu", "EN_ATTENTE").param("nouveau", "EN_CONSULTATION"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("depuis un autre poste")));
        mvc.perform(post("/queue/" + e1 + "/statut").with(user(doctor)).with(csrf())
                .param("attendu", "EN_CONSULTATION").param("nouveau", "TERMINE"));
        mvc.perform(post("/queue/" + e2 + "/statut").with(user(doctor)).with(csrf())
                .param("attendu", "EN_ATTENTE").param("nouveau", "EN_CONSULTATION"));
        assertThat(count(doctor, "SELECT count(*) FROM file_attente WHERE statut = 'EN_CONSULTATION' AND patient_id = ?::uuid", p2)).isEqualTo(1);
        // remettre en attente, retirer
        mvc.perform(post("/queue/" + e2 + "/statut").with(user(doctor)).with(csrf())
                .param("attendu", "EN_CONSULTATION").param("nouveau", "EN_ATTENTE"));
        mvc.perform(post("/queue/" + e2 + "/retirer").with(user(doctor)).with(csrf()))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Deux"))));
        // ajout htmx avec un état prioritaire, puis recherche : le patient est signalé déjà présent
        mvc.perform(post("/queue").with(user(doctor)).with(csrf()).param("patientId", p2).param("etat", "URGENT"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Urgent")));
        mvc.perform(get("/queue/recherche").with(user(doctor)).param("q", "deux"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Déjà dans la file")));
        mvc.perform(get("/queue/list").with(user(doctor)).header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<html"))));
    }

    private UUID firstEntry(MedsolUser u, String patientId) {
        TenantContext.set(u.cabinetId());
        try {
            return jdbc.queryForObject("SELECT id FROM file_attente WHERE patient_id = ?::uuid", UUID.class, patientId);
        } finally { TenantContext.clear(); }
    }

    @Test
    void planningLikeTheDesktop() throws Exception {
        String p = createPatient(doctor, "Rdv", "Patient");
        String date = "2027-03-10";   // un mercredi

        mvc.perform(get("/planning").with(user(doctor))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("fullcalendar.min.js")));
        mvc.perform(post("/planning").with(user(doctor)).with(csrf())
                        .param("patientId", p).param("date", date).param("heure", "10:00").param("duree", "30"))
                .andExpect(header().string("HX-Trigger", "rdvChange"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Rendez-vous enregistré")));
        // créneau occupé : confirmation demandée, puis « enregistrer quand même » accepté (comme le bureau)
        mvc.perform(post("/planning").with(user(doctor)).with(csrf())
                        .param("patientId", p).param("date", date).param("heure", "10:15").param("duree", "30"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Créneau déjà occupé")));
        mvc.perform(post("/planning").with(user(doctor)).with(csrf())
                        .param("patientId", p).param("date", date).param("heure", "10:15").param("duree", "30").param("forcer", "true"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Rendez-vous enregistré")));
        assertThat(count(doctor, "SELECT count(*) FROM rendez_vous")).isEqualTo(2);

        // événements du calendrier (JSON) et indicateurs
        mvc.perform(get("/planning/evenements").with(user(doctor)).param("start", "2027-03-08").param("end", "2027-03-15"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].start").value("2027-03-10T10:00"));
        mvc.perform(get("/planning/indicateurs").with(user(doctor)).param("start", "2027-03-08").param("end", "2027-03-15"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("rendez-vous")));

        UUID first = UUID.fromString(text(doctor, "SELECT id::text FROM rendez_vous ORDER BY debut LIMIT 1"));
        // transitions du bureau : Planifié → Confirmé permis, Planifié → Terminé refusé
        mvc.perform(post("/planning/" + first + "/statut").with(user(doctor)).with(csrf()).param("attendu", "PLANIFIE").param("nouveau", "CONFIRME"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Rendez-vous confirmé.")));
        mvc.perform(post("/planning/" + first + "/statut").with(user(doctor)).with(csrf()).param("attendu", "CONFIRME").param("nouveau", "TERMINE"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Passage impossible")));
        // état périmé (un autre poste l'a déjà changé)
        mvc.perform(post("/planning/" + first + "/statut").with(user(doctor)).with(csrf()).param("attendu", "PLANIFIE").param("nouveau", "ANNULE"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("depuis un autre poste")));
        // glisser-déposer sur un créneau pris : refusé ; report par le panneau : journal
        mvc.perform(post("/planning/" + first + "/deplacer").with(user(doctor)).with(csrf())
                        .param("attendu", "CONFIRME").param("debut", "2027-03-10T10:15:00").param("fin", "2027-03-10T10:45:00"))
                .andExpect(jsonPath("$.ok").value(false));
        mvc.perform(post("/planning/" + first + "/reporter").with(user(doctor)).with(csrf())
                        .param("attendu", "CONFIRME").param("date", "2027-03-11").param("heure", "09:00").param("motif", "demande du patient"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Reporté au 11/03/2027 à 09:00")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Confirmé → Reporté")));
        assertThat(count(doctor, "SELECT count(*) FROM rdv_journal WHERE rdv_id = ?::uuid", first.toString())).isEqualTo(3);

        // horaires du cabinet : un dimanche demande une confirmation
        mvc.perform(post("/settings/horaires").with(user(doctor)).with(csrf()).param("d1_1", "09:00").param("f1_1", "13:00")
                .param("d3_1", "09:00").param("f3_1", "13:00")).andExpect(flash().attribute("done", "settings.saved"));
        mvc.perform(post("/planning").with(user(doctor)).with(csrf())
                        .param("patientId", p).param("date", "2027-03-14").param("heure", "10:00").param("duree", "30"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Le cabinet est fermé le dimanche")));
        mvc.perform(get("/planning").with(user(doctor)))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("lun. 09:00–13:00")));

        // arrivée d'un rendez-vous du jour : salle d'attente et « En cours »
        java.time.LocalDate aujourdhui = java.time.LocalDate.now(java.time.ZoneId.of("Africa/Casablanca"));
        mvc.perform(post("/planning").with(user(doctor)).with(csrf()).param("forcer", "true")
                .param("patientId", p).param("date", aujourdhui.toString()).param("heure", "08:00").param("duree", "15"));
        UUID duJour = UUID.fromString(text(doctor, "SELECT id::text FROM rendez_vous WHERE motif IS NULL ORDER BY cree_le DESC LIMIT 1"));
        mvc.perform(post("/planning/" + duJour + "/arrive").with(user(doctor)).with(csrf()))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Ajouté(e) à la salle d")));
        assertThat(text(doctor, "SELECT statut FROM rendez_vous WHERE id = ?::uuid", duJour.toString())).isEqualTo("EN_COURS");
        assertThat(count(doctor, "SELECT count(*) FROM file_attente WHERE rdv_id = ?::uuid", duJour.toString())).isEqualTo(1);

        // autre cabinet : introuvable
        MedsolUser other = newDoctor();
        mvc.perform(get("/planning/" + first).with(user(other))).andExpect(status().isNotFound());
    }

    private String text(MedsolUser u, String sql, Object... args) {
        TenantContext.set(u.cabinetId());
        try { return jdbc.queryForObject(sql, String.class, args); } finally { TenantContext.clear(); }
    }
}