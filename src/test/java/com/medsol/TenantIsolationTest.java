package com.medsol;

import com.medsol.cabinet.RegistrationForm;
import com.medsol.cabinet.RegistrationService;
import com.medsol.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class TenantIsolationTest extends AbstractDbTest {

    @Autowired RegistrationService registration;
    @Autowired JdbcTemplate jdbc;

    private UUID cabinet(String name, String email) {
        RegistrationForm f = new RegistrationForm();
        f.setCabinetName(name); f.setDoctorName("Dr " + name); f.setEmail(email);
        f.setPassword("motdepasse-long"); f.setCountry("MA"); f.setSpecialty("CARDIO");
        return registration.register(f, "fr");
    }

    private void addPatient(UUID cabinet, String nom) {
        TenantContext.set(cabinet);
        try {
            jdbc.update("INSERT INTO patient (cabinet_id, nom, prenom) VALUES (?, ?, 'X')", cabinet, nom);
        } finally { TenantContext.clear(); }
    }

    private long countPatients(UUID cabinet) {
        TenantContext.set(cabinet);
        try { return jdbc.queryForObject("SELECT count(*) FROM patient", Long.class); }
        finally { TenantContext.clear(); }
    }

    @Test
    void cabinetsAreIsolated() {
        UUID a = cabinet("A", "a@test.com");
        UUID b = cabinet("B", "b@test.com");
        addPatient(a, "Alpha"); addPatient(a, "Beta"); addPatient(b, "Gamma");

        assertThat(countPatients(a)).isEqualTo(2);
        assertThat(countPatients(b)).isEqualTo(1);
    }

    @Test
    void noTenantSeesNothing() {
        UUID a = cabinet("C", "c@test.com");
        addPatient(a, "Delta");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM patient", Long.class)).isZero();
    }

    @Test
    void cannotWriteInAnotherCabinet() {
        UUID a = cabinet("D", "d@test.com");
        UUID b = cabinet("E", "e@test.com");
        TenantContext.set(a);
        try {
            assertThatThrownBy(() ->
                    jdbc.update("INSERT INTO patient (cabinet_id, nom, prenom) VALUES (?, 'Intrus', 'X')", b))
                    .isInstanceOf(DataAccessException.class);
        } finally { TenantContext.clear(); }
    }

    @Test
    void emailsAreUniquePlatformWide() {
        cabinet("F", "f@test.com");
        assertThat(registration.emailTaken("F@TEST.com")).isTrue();
    }

    @Test
    void auditLogCannotBeAltered() {
        UUID a = cabinet("G", "g@test.com");
        TenantContext.set(a);
        try {
            jdbc.update("INSERT INTO journal_audit (cabinet_id, action, objet) VALUES (?, 'TEST', 'x')", a);
            assertThatThrownBy(() -> jdbc.update("UPDATE journal_audit SET action = 'X'"))
                    .isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> jdbc.update("DELETE FROM journal_audit"))
                    .isInstanceOf(DataAccessException.class);
        } finally { TenantContext.clear(); }
    }
}
