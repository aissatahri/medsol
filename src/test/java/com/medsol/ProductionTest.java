package com.medsol;

import com.medsol.dossier.ConsultationRepository;
import com.medsol.security.DoubleAuthentification;
import com.medsol.security.MedsolUser;
import com.medsol.security.Messagerie;
import com.medsol.security.Totp;
import com.medsol.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Prêt pour la production : codes de secours et chiffrement de la double authentification, limitation par IP, export. */
class ProductionTest extends WebTestSupport {

    @Autowired @Qualifier("plateforme") JdbcTemplate plateforme;
    @Autowired DoubleAuthentification deuxFacteurs;
    @Autowired ConsultationRepository consultations;
    @Autowired Messagerie messagerie;

    private MockHttpSession login(String email, String password, String attendu) throws Exception {
        MockHttpSession session = new MockHttpSession();
        MvcResult page = mvc.perform(get("/login").session(session)).andReturn();
        Matcher m = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(page.getResponse().getContentAsString());
        assertThat(m.find()).isTrue();
        MvcResult r = mvc.perform(post("/login").session(session).param("username", email).param("password", password).param("_csrf", m.group(1)))
                .andExpect(redirectedUrl(attendu)).andReturn();
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void backupCodesWorkOnceAndTheTotpKeyIsEncrypted() throws Exception {
        MockHttpSession s = login(doctor.email(), "motdepasse-long", "/");
        mvc.perform(post("/compte/2fa/preparer").session(s).with(csrf()));
        String secret = (String) s.getAttribute("medsol.2fa.secret");
        MvcResult r = mvc.perform(post("/compte/2fa/activer").session(s).with(csrf()).param("code", Totp.code(secret, Instant.now())))
                .andExpect(flash().attribute("done", "account.2fa.enabled")).andReturn();
        List<String> codes = (List<String>) r.getFlashMap().get("codes");
        assertThat(codes).hasSize(10).allMatch(c -> c.matches("[A-Z2-9]{5}-[A-Z2-9]{5}"));

        // clé chiffrée en base : ni la clé ni un morceau ne s'y lit
        String enBase = plateforme.queryForObject("SELECT totp_secret FROM utilisateur WHERE id = ?", String.class, doctor.id());
        assertThat(enBase).startsWith("v1:").doesNotContain(secret.substring(0, 8));
        assertThat(plateforme.queryForObject("SELECT count(*) FROM code_secours WHERE utilisateur_id = ?", Long.class, doctor.id())).isEqualTo(10);

        // code de secours (minuscules et sans tiret acceptés) : une seule fois
        MockHttpSession a = login(doctor.email(), "motdepasse-long", "/2fa");
        mvc.perform(post("/2fa").session(a).with(csrf()).param("code", codes.get(0).replace("-", "").toLowerCase()))
                .andExpect(redirectedUrl("/compte#deux-facteurs")).andExpect(flash().attribute("info", "account.2fa.backup.used"));
        mvc.perform(get("/compte").session(a)).andExpect(content().string(org.hamcrest.Matchers.containsString("9 code(s)")));
        MockHttpSession b = login(doctor.email(), "motdepasse-long", "/2fa");
        mvc.perform(post("/2fa").session(b).with(csrf()).param("code", codes.get(0))).andExpect(redirectedUrl("/2fa?erreur"));

        // même code à 6 chiffres : accepté une fois seulement
        String suivant = Totp.code(secret, Instant.now().plusSeconds(30));
        mvc.perform(post("/2fa").session(b).with(csrf()).param("code", suivant)).andExpect(redirectedUrl("/"));
        MockHttpSession c = login(doctor.email(), "motdepasse-long", "/2fa");
        mvc.perform(post("/2fa").session(c).with(csrf()).param("code", suivant)).andExpect(redirectedUrl("/2fa?erreur"));

        // nouveaux codes : les anciens ne valent plus rien
        MvcResult n = mvc.perform(post("/compte/2fa/codes").session(b).with(csrf()).param("ancien", "motdepasse-long")).andReturn();
        assertThat((List<String>) n.getFlashMap().get("codes")).hasSize(10).doesNotContain(codes.get(1));
        mvc.perform(post("/2fa").session(c).with(csrf()).param("code", codes.get(1))).andExpect(redirectedUrl("/2fa?erreur"));
    }

    @Test
    void platformAdminRemovesADoctorsTwoFactorAuthentication() throws Exception {
        deuxFacteurs.activer(doctor.id(), Totp.nouveauSecret(), 0);
        MedsolUser autre = newDoctor("GENERAL");
        MedsolUser admin = new MedsolUser(autre.id(), autre.cabinetId(), autre.email(), autre.passwordHash(), autre.fullName(),
                autre.role(), true, null, true);
        mvc.perform(post("/plateforme/cabinets/" + doctor.cabinetId() + "/2fa").with(user(autre)).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/plateforme/cabinets/" + doctor.cabinetId() + "/2fa").with(user(admin)).with(csrf()))
                .andExpect(flash().attributeExists("succes"));
        assertThat(((MedsolUser) accounts.loadUserByUsername(doctor.email())).totp()).isFalse();
        assertThat(plateforme.queryForObject("SELECT count(*) FROM code_secours WHERE utilisateur_id = ?", Long.class, doctor.id())).isZero();
        assertThat(count(doctor, "SELECT count(*) FROM journal_audit WHERE action = 'DOUBLE_AUTH_RETIREE_PLATEFORME'")).isEqualTo(1);
        // sessions du médecin fermées
        mvc.perform(get("/patients").with(user(doctor))).andExpect(redirectedUrl("/login?expire"));
    }

    @Test
    void tooManyAttemptsFromOneAddressAreBlocked() throws Exception {
        String inconnu = "robot-" + UUID.randomUUID() + "@test.com";
        for (int i = 0; i < 20; i++) {
            mvc.perform(post("/login").with(csrf()).param("username", inconnu).param("password", "x" + i)).andExpect(redirectedUrl("/login?error"));
        }
        mvc.perform(post("/login").with(csrf()).param("username", doctor.email()).param("password", "motdepasse-long"))
                .andExpect(redirectedUrl("/login?trop")).andExpect(header().exists("Retry-After"));
        mvc.perform(get("/login").param("trop", "")).andExpect(content().string(org.hamcrest.Matchers.containsString("Trop de tentatives")));
        // une autre adresse n'est pas touchée
        mvc.perform(post("/login").with(csrf()).with(r -> { r.setRemoteAddr("10.1.2.3"); return r; })
                .param("username", inconnu).param("password", "x")).andExpect(redirectedUrl("/login?error"));

        // mot de passe oublié : au plus 3 e-mails par heure pour une même adresse
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/mot-de-passe-oublie").with(csrf()).with(r -> { r.setRemoteAddr("10.9.9.9"); return r; }).param("email", doctor.email()));
        }
        Messagerie.Mail troisieme = messagerie.dernier();
        mvc.perform(post("/mot-de-passe-oublie").with(csrf()).with(r -> { r.setRemoteAddr("10.9.9.8"); return r; }).param("email", doctor.email()))
                .andExpect(redirectedUrl("/mot-de-passe-oublie?envoye"));
        assertThat(messagerie.dernier()).isSameAs(troisieme);
    }

    @Test
    void doctorExportsTheCompletePatientRecord() throws Exception {
        String p = patient(doctor, "Exporté");
        UUID patientId = UUID.fromString(p);
        TenantContext.set(doctor.cabinetId());
        try {
            jdbc.update("INSERT INTO allergie (cabinet_id, patient_id, nom) VALUES (current_cabinet(), ?, 'Pénicilline')", patientId);
            consultations.duJour(patientId, doctor.id(), LocalDate.now());
        } finally {
            TenantContext.clear();
        }
        mvc.perform(multipart("/patients/" + p + "/fichiers").file(new MockMultipartFile("fichier", "scan.pdf", "application/pdf",
                "%PDF-1.4\n%resultat\n".getBytes(StandardCharsets.US_ASCII))).with(user(doctor)).with(csrf()));

        MvcResult r = mvc.perform(get("/patients/" + p + "/export").with(user(doctor)))
                .andExpect(status().isOk()).andExpect(content().contentType("application/zip"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("dossier-exporte-p-")))
                .andReturn();
        Map<String, byte[]> zip = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(r.getResponse().getContentAsByteArray()))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) zip.put(e.getName(), in.readAllBytes());
        }
        assertThat(zip).containsKeys("dossier.json", "dossier.html", "LISEZMOI.txt");
        String json = new String(zip.get("dossier.json"), StandardCharsets.UTF_8);
        assertThat(json).contains("\"format\" : \"medsol-dossier-patient\"", "Exporté", "Pénicilline", "\"consultation\"", "\"fichier\"")
                .doesNotContain("cabinet_id", "mot_de_passe");
        assertThat(new String(zip.get("dossier.html"), StandardCharsets.UTF_8)).contains("Pénicilline", "scan.pdf");
        assertThat(zip.keySet()).anyMatch(k -> k.startsWith("fichiers/fichier/") && k.endsWith("scan.pdf"));
        assertThat(zip.entrySet().stream().filter(e -> e.getKey().endsWith("scan.pdf")).findFirst().orElseThrow().getValue())
                .startsWith("%PDF".getBytes(StandardCharsets.US_ASCII));
        assertThat(count(doctor, "SELECT count(*) FROM journal_audit WHERE action = 'EXPORT_DOSSIER'")).isEqualTo(1);

        // assistante : refusé ; autre cabinet : introuvable
        MedsolUser autre = newDoctor("GENERAL");
        mvc.perform(get("/patients/" + p + "/export").with(user(autre))).andExpect(status().isNotFound());
    }
}
