package com.medsol;

import com.medsol.security.MedsolUser;
import com.medsol.security.Messagerie;
import com.medsol.security.Totp;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Comptes : Mon compte, mot de passe oublié, invitation, double authentification, fermeture des sessions,
 * droits de l'assistante, historique par compte.
 */
class ComptesSecuriteTest extends WebTestSupport {

    @Autowired Messagerie messagerie;
    @Autowired PasswordEncoder encoder;
    @Autowired @Qualifier("plateforme") JdbcTemplate plateforme;

    private MockHttpSession login(String email, String password, String attendu) throws Exception {
        MockHttpSession session = new MockHttpSession();
        MvcResult page = mvc.perform(get("/login").session(session)).andExpect(status().isOk()).andReturn();
        Matcher m = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(page.getResponse().getContentAsString());
        assertThat(m.find()).isTrue();
        MvcResult r = mvc.perform(post("/login").session(session).param("username", email).param("password", password).param("_csrf", m.group(1)))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl(attendu)).andReturn();
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    private MedsolUser assistante(String... droits) throws Exception {
        String email = "a-" + UUID.randomUUID() + "@test.com";
        var req = post("/settings/users").with(user(doctor)).with(csrf()).param("fullName", "Nadia").param("email", email)
                .param("password", "un-mot-de-passe").param("confirmation", "un-mot-de-passe").param("reglerDroits", "true");
        for (String d : droits) req = req.param("droits", d);
        mvc.perform(req).andExpect(flash().attribute("done", "users.created"));
        return (MedsolUser) accounts.loadUserByUsername(email);
    }

    private static String lien(String texte, String chemin) {
        Matcher m = Pattern.compile(chemin + "([A-Za-z0-9_-]+)").matcher(texte);
        assertThat(m.find()).as("lien " + chemin + " dans l'e-mail").isTrue();
        return m.group(1);
    }

    @Test
    void totpFollowsRfc6238() {
        // vecteur de test de la RFC 6238 (clé ASCII « 12345678901234567890 », T = 59 s) : 94287082 → 6 chiffres 287082
        String cle = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
        assertThat(Totp.verifier(cle, "287082", Instant.ofEpochSecond(59))).isTrue();
        assertThat(Totp.verifier(cle, "287083", Instant.ofEpochSecond(59))).isFalse();
        assertThat(Totp.verifier(cle, "287082", Instant.ofEpochSecond(59 + 300))).isFalse();
        assertThat(Totp.nouveauSecret()).matches("[A-Z2-7]{32}");
    }

    @Test
    void myAccountChangesPasswordAndClosesOtherSessions() throws Exception {
        MockHttpSession autreAppareil = login(doctor.email(), "motdepasse-long", "/");
        mvc.perform(get("/compte").with(user(doctor))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Double authentification")));

        mvc.perform(post("/compte/mot-de-passe").with(user(doctor)).with(csrf())
                        .param("ancien", "faux-mot-de-passe").param("password", "nouveau-mot-de-passe").param("confirmation", "nouveau-mot-de-passe"))
                .andExpect(flash().attribute("error", "account.password.wrong"));
        mvc.perform(post("/compte/mot-de-passe").with(user(doctor)).with(csrf())
                        .param("ancien", "motdepasse-long").param("password", "nouveau-mot-de-passe").param("confirmation", "nouveau-mot-de-passe"))
                .andExpect(flash().attribute("done", "account.password.changed"));

        // l'autre appareil est déconnecté à sa requête suivante
        mvc.perform(get("/patients").session(autreAppareil)).andExpect(redirectedUrl("/login?expire"));
        login(doctor.email(), "nouveau-mot-de-passe", "/");

        // l'ancienne version du compte n'est plus acceptée : on relit le compte
        mvc.perform(get("/patients").with(user(doctor))).andExpect(redirectedUrl("/login?expire"));
        doctor = (MedsolUser) accounts.loadUserByUsername(doctor.email());
        mvc.perform(post("/compte/profil").with(user(doctor)).with(csrf()).param("fullName", "Dr Nouveau Nom").param("langue", "en"))
                .andExpect(flash().attribute("done", "account.saved"));
        MedsolUser relu = (MedsolUser) accounts.loadUserByUsername(doctor.email());
        assertThat(relu.fullName()).isEqualTo("Dr Nouveau Nom");
        assertThat(relu.langue()).isEqualTo("en");
    }

    @Test
    void disablingAnAccountEndsItsSessionImmediately() throws Exception {
        MedsolUser a = assistante("CAISSE", "RESULTATS");
        MockHttpSession s = login(a.email(), "un-mot-de-passe", "/");
        mvc.perform(get("/patients").session(s)).andExpect(status().isOk());
        mvc.perform(post("/settings/users/" + a.id() + "/active").with(user(doctor)).with(csrf()).param("value", "false"));
        mvc.perform(get("/patients").session(s)).andExpect(redirectedUrl("/login?expire"));
    }

    @Test
    void forgottenPasswordSendsASingleUseLinkValidThirtyMinutes() throws Exception {
        Messagerie.Mail avant = messagerie.dernier();
        mvc.perform(post("/mot-de-passe-oublie").with(csrf()).param("email", "inconnu-" + UUID.randomUUID() + "@test.com"))
                .andExpect(redirectedUrl("/mot-de-passe-oublie?envoye"));
        assertThat(messagerie.dernier()).isSameAs(avant);

        mvc.perform(post("/mot-de-passe-oublie").with(csrf()).param("email", doctor.email().toUpperCase()))
                .andExpect(redirectedUrl("/mot-de-passe-oublie?envoye"));
        assertThat(messagerie.dernier().a()).isEqualTo(doctor.email());
        assertThat(messagerie.dernier().texte()).contains("30");
        String jeton = lien(messagerie.dernier().texte(), "/reinitialisation/");

        mvc.perform(get("/reinitialisation/" + jeton)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"password\"")));
        mvc.perform(post("/reinitialisation/" + jeton).with(csrf()).param("password", "court").param("confirmation", "court"))
                .andExpect(status().isOk()).andExpect(model().attribute("error", "users.password.short"));
        mvc.perform(post("/reinitialisation/" + jeton).with(csrf()).param("password", "mot-de-passe-retrouve").param("confirmation", "mot-de-passe-retrouve"))
                .andExpect(redirectedUrl("/login?reinitialise"));
        login(doctor.email(), "mot-de-passe-retrouve", "/");

        // usage unique
        mvc.perform(get("/reinitialisation/" + jeton)).andExpect(model().attribute("compte", org.hamcrest.Matchers.nullValue()));
        mvc.perform(post("/reinitialisation/" + jeton).with(csrf()).param("password", "encore-un-autre").param("confirmation", "encore-un-autre"))
                .andExpect(status().isOk());
        assertThat(encoder.matches("encore-un-autre", ((MedsolUser) accounts.loadUserByUsername(doctor.email())).passwordHash())).isFalse();

        // expiration après 30 minutes
        mvc.perform(post("/mot-de-passe-oublie").with(csrf()).param("email", doctor.email()));
        String perime = lien(messagerie.dernier().texte(), "/reinitialisation/");
        plateforme.update("UPDATE jeton SET expire_le = now() - interval '1 minute' WHERE utilisateur_id = ? AND utilise_le IS NULL", doctor.id());
        mvc.perform(get("/reinitialisation/" + perime)).andExpect(model().attribute("compte", org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void invitedAssistantChoosesHerOwnPassword() throws Exception {
        String email = "inv-" + UUID.randomUUID() + "@test.com";
        mvc.perform(post("/settings/users").with(user(doctor)).with(csrf()).param("fullName", "Salma").param("email", email)
                        .param("inviter", "true").param("reglerDroits", "true").param("droits", "CAISSE"))
                .andExpect(flash().attribute("done", "users.invited"));
        assertThat(messagerie.dernier().a()).isEqualTo(email);
        mvc.perform(get("/settings/users").with(user(doctor)))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Invitation envoyée")));
        String jeton = lien(messagerie.dernier().texte(), "/invitation/");

        mvc.perform(get("/invitation/" + jeton)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Salma")));
        mvc.perform(post("/invitation/" + jeton).with(csrf()).param("password", "mon-propre-secret").param("confirmation", "mon-propre-secret"))
                .andExpect(redirectedUrl("/login?bienvenue"));
        MockHttpSession s = login(email, "mon-propre-secret", "/");
        mvc.perform(get("/caisse").session(s)).andExpect(status().isOk());
        mvc.perform(get("/patients/" + patient(doctor, "Fichier") + "/fichiers").session(s)).andExpect(status().isForbidden());
    }

    @Test
    void assistantRightsAreEnforcedAndAppliedWithoutLogout() throws Exception {
        MedsolUser a = assistante("RESULTATS");
        String p = patient(doctor, "Droits");
        mvc.perform(get("/caisse").with(user(a))).andExpect(status().isForbidden());
        mvc.perform(get("/patients/" + p + "/facture").with(user(a))).andExpect(status().isForbidden());
        mvc.perform(get("/patients/" + p + "/fichiers").with(user(a))).andExpect(status().isOk());
        mvc.perform(get("/patients").with(user(a))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("href=\"/caisse\""))));

        MockHttpSession s = login(a.email(), "un-mot-de-passe", "/");
        mvc.perform(post("/settings/users/" + a.id() + "/droits").with(user(doctor)).with(csrf()).param("droits", "CAISSE"))
                .andExpect(flash().attribute("done", "users.rights.saved"));
        // session toujours ouverte, nouveaux droits appliqués
        mvc.perform(get("/caisse").session(s)).andExpect(status().isOk());
        mvc.perform(get("/patients/" + p + "/fichiers").session(s)).andExpect(status().isForbidden());
    }

    @Test
    void twoFactorAuthenticationIsAskedAfterThePassword() throws Exception {
        MockHttpSession s = login(doctor.email(), "motdepasse-long", "/");
        mvc.perform(post("/compte/2fa/preparer").session(s).with(csrf())).andExpect(status().is3xxRedirection());
        mvc.perform(get("/compte").session(s)).andExpect(content().string(org.hamcrest.Matchers.containsString("<svg")));
        String secret = (String) s.getAttribute("medsol.2fa.secret");
        mvc.perform(post("/compte/2fa/activer").session(s).with(csrf()).param("code", "000000"))
                .andExpect(flash().attribute("error", "account.2fa.badcode"));
        mvc.perform(post("/compte/2fa/activer").session(s).with(csrf()).param("code", Totp.code(secret, Instant.now())))
                .andExpect(flash().attribute("done", "account.2fa.enabled"));
        assertThat(((MedsolUser) accounts.loadUserByUsername(doctor.email())).totp()).isTrue();

        MockHttpSession n = login(doctor.email(), "motdepasse-long", "/2fa");
        mvc.perform(get("/patients").session(n)).andExpect(redirectedUrl("/2fa"));
        mvc.perform(get("/2fa").session(n)).andExpect(status().isOk());
        mvc.perform(post("/2fa").session(n).with(csrf()).param("code", "123456")).andExpect(redirectedUrl("/2fa?erreur"));
        // le code de l'activation ne resservira pas (anti-rejeu) : celui du pas suivant est accepté
        mvc.perform(post("/2fa").session(n).with(csrf()).param("code", Totp.code(secret, Instant.now()))).andExpect(redirectedUrl("/2fa?erreur"));
        mvc.perform(post("/2fa").session(n).with(csrf()).param("code", Totp.code(secret, Instant.now().plusSeconds(30)))).andExpect(redirectedUrl("/"));
        mvc.perform(get("/patients").session(n)).andExpect(status().isOk());
        assertThat(count(doctor, "SELECT count(*) FROM journal_audit WHERE action = 'ECHEC_2FA'")).isEqualTo(2);
    }

    @Test
    void historyLinkFiltersTheAccessLogOnOneAccount() throws Exception {
        MedsolUser a = assistante("CAISSE");
        login(a.email(), "un-mot-de-passe", "/");
        mvc.perform(get("/settings/users").with(user(doctor)))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/settings/journal?u=" + a.id())));
        mvc.perform(get("/settings/journal").with(user(doctor)).param("u", a.id().toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("CONNEXION")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("UTILISATEUR_CREATION"))));
        mvc.perform(get("/settings/journal").with(user(doctor)))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("UTILISATEUR_CREATION")));
    }
}
