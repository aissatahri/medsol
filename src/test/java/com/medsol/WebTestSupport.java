package com.medsol;

import com.medsol.cabinet.RegistrationForm;
import com.medsol.cabinet.RegistrationService;
import com.medsol.security.AccountService;
import com.medsol.security.MedsolUser;
import com.medsol.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Outils communs des tests web : un cabinet neuf par test, création de patients, lectures dans le cabinet. */
@SpringBootTest
abstract class WebTestSupport extends AbstractDbTest {

    @Autowired WebApplicationContext ctx;
    @Autowired RegistrationService registration;
    @Autowired AccountService accounts;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.medsol.security.LimiteurTentatives limiteur;

    MockMvc mvc;
    MedsolUser doctor;

    @BeforeEach
    void setUpWeb() {
        mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        limiteur.reinitialiser();
        doctor = newDoctor("CARDIO");
    }

    MedsolUser newDoctor(String specialty) {
        String email = "doc-" + UUID.randomUUID() + "@test.com";
        RegistrationForm f = new RegistrationForm();
        f.setCabinetName("Cab " + email); f.setDoctorName("Dr Test"); f.setEmail(email);
        f.setPassword("motdepasse-long"); f.setCountry("MA"); f.setSpecialty(specialty);
        f.setTimezone("Africa/Casablanca"); f.setCurrency("MAD"); f.setCgu(true);
        registration.register(f, "fr");
        return (MedsolUser) accounts.loadUserByUsername(email);
    }

    String patient(MedsolUser u, String nom) throws Exception {
        String loc = mvc.perform(post("/patients").with(user(u)).with(csrf())
                        .param("nom", nom).param("prenom", "P").param("consentement", "true"))
                .andReturn().getResponse().getRedirectedUrl();
        return loc.substring(loc.lastIndexOf('/') + 1);
    }

    long count(MedsolUser u, String sql, Object... args) {
        TenantContext.set(u.cabinetId());
        try { return jdbc.queryForObject(sql, Long.class, args); } finally { TenantContext.clear(); }
    }

    String text(MedsolUser u, String sql, Object... args) {
        TenantContext.set(u.cabinetId());
        try { return jdbc.queryForObject(sql, String.class, args); } finally { TenantContext.clear(); }
    }
}
