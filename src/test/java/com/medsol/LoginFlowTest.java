package com.medsol;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Connexion réelle par le formulaire (jeton CSRF de la page), pour le médecin et l'assistante. */
class LoginFlowTest extends WebTestSupport {

    private MockHttpSession login(String email, String password) throws Exception {
        MockHttpSession session = new MockHttpSession();
        MvcResult page = mvc.perform(get("/login").session(session)).andExpect(status().isOk()).andReturn();
        Matcher m = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(page.getResponse().getContentAsString());
        assertThat(m.find()).as("jeton CSRF dans le formulaire de connexion").isTrue();
        mvc.perform(post("/login").session(session).param("username", email).param("password", password).param("_csrf", m.group(1)))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/"));
        return session;
    }

    @Test
    void doctorAndAssistantCanSignInThroughTheForm() throws Exception {
        MockHttpSession s = login(doctor.email(), "motdepasse-long");
        mvc.perform(get("/").session(s)).andExpect(status().isOk());
        mvc.perform(get("/patients").session(s)).andExpect(status().isOk());

        mvc.perform(post("/settings/users").session(s)
                .param("fullName", "Assistante").param("email", "a-" + doctor.cabinetId() + "@test.com").param("password", "un-mot-de-passe")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()));
        MockHttpSession a = login("a-" + doctor.cabinetId() + "@test.com", "un-mot-de-passe");
        mvc.perform(get("/").session(a)).andExpect(status().isOk());
        mvc.perform(get("/queue").session(a)).andExpect(status().isOk());
    }

    @Test
    void staleLoginPageDoesNotEndOnAnErrorPage() throws Exception {
        // Page de connexion ouverte avant un redémarrage du serveur : jeton périmé
        mvc.perform(post("/login").param("username", doctor.email()).param("password", "motdepasse-long").param("_csrf", "perime"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login?expire"));
    }

    @Test
    void staticResourcesAreServedWithoutSession() throws Exception {
        mvc.perform(get("/img/favicon-32.png")).andExpect(status().isOk());
        mvc.perform(get("/css/app.css")).andExpect(status().isOk());
    }
}
