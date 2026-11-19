package com.medsol;

import com.medsol.security.MedsolUser;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Les écrans du cabinet en anglais et en espagnol : aucune clé manquante (« ??clé?? ») et contenu traduit. */
class EcransLanguesTest extends WebTestSupport {

    private String page(MedsolUser u, String url, String langue) throws Exception {
        return mvc.perform(get(url).with(user(u)).cookie(new Cookie("medsol-lang", langue)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void mainScreensAreTranslated() throws Exception {
        String p = patient(doctor, "Garcia");
        String email = "a-" + UUID.randomUUID() + "@test.com";
        mvc.perform(post("/settings/users").with(user(doctor)).with(csrf()).param("fullName", "Lucia").param("email", email)
                .param("password", "un-mot-de-passe").param("confirmation", "un-mot-de-passe"));
        MedsolUser assistante = (MedsolUser) accounts.loadUserByUsername(email);

        // écran → mot attendu en anglais, en espagnol
        Map<String, String[]> medecin = new LinkedHashMap<>();
        medecin.put("/", new String[]{"Waiting list", "Lista de espera"});
        medecin.put("/patients", new String[]{"New patient", "Nuevo paciente"});
        medecin.put("/patients/" + p, new String[]{"Contact and personal details", "Datos de contacto y personales"});
        medecin.put("/patients/" + p + "/dossier", new String[]{"Patient information", "Datos del paciente"});
        medecin.put("/patients/" + p + "/dossier?onglet=consultation", new String[]{"Consultation date", "Fecha de consulta"});
        medecin.put("/patients/" + p + "/dossier?onglet=courbes", new String[]{"Vital signs over time", "Evolución de las constantes"});
        medecin.put("/planning", new String[]{"Tomorrow’s reminders", "Recordatorios de mañana"});
        medecin.put("/planning/rappels", new String[]{"Appointment reminders", "Recordatorios de citas"});
        medecin.put("/caisse", new String[]{"Collected today", "Cobrado hoy"});
        medecin.put("/statistiques", new String[]{"Month-by-month activity", "Actividad mes a mes"});
        medecin.put("/settings/users", new String[]{"Add an assistant", "Añadir una asistente"});
        medecin.put("/settings/journal", new String[]{"Access log", "Registro de accesos"});
        medecin.put("/settings/consultation", new String[]{"Clinical findings", "Resultados clínicos"});
        medecin.put("/compte", new String[]{"My account", "Mi cuenta"});
        medecin.put("/abonnement", new String[]{"Per month", "Al mes"});
        medecin.put("/consultations/corbeille", new String[]{"Consultation recycle bin", "Papelera de consultas"});
        for (var e : medecin.entrySet()) {
            String en = page(doctor, e.getKey(), "en");
            String es = page(doctor, e.getKey(), "es");
            assertThat(en).as(e.getKey() + " en").doesNotContain("??").contains(e.getValue()[0]).contains("Practice messaging");
            assertThat(es).as(e.getKey() + " es").doesNotContain("??").contains(e.getValue()[1]).contains("Mensajería del consultorio");
        }
        // l'assistante : salle d'attente
        assertThat(page(assistante, "/queue", "es")).doesNotContain("??").contains("Añadir un paciente").contains("Conversación con el médico");
        assertThat(page(assistante, "/queue", "en")).doesNotContain("??").contains("Add a patient").contains("Chat with the doctor");
    }
}
