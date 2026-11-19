package com.medsol;

import com.medsol.config.DemoData;
import com.medsol.security.MedsolUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DemoDataTest extends WebTestSupport {

    @Autowired PasswordEncoder encoder;

    @Test
    void createsThreeCabinetsWithAccountsAndActivityOnceOnly() throws Exception {
        DemoData demo = new DemoData(registration, jdbc, encoder, false);
        demo.run(null);
        demo.run(null);   // idempotent

        MedsolUser cardio = (MedsolUser) accounts.loadUserByUsername("medecin.cardio@demo.medsol");
        MedsolUser assistante = (MedsolUser) accounts.loadUserByUsername("assistante.cardio@demo.medsol");
        assertThat(encoder.matches("Cardio-Demo-2026", cardio.getPassword())).isTrue();
        assertThat(assistante.role()).isEqualTo("SECRETAIRE");
        assertThat(assistante.cabinetId()).isEqualTo(cardio.cabinetId());

        assertThat(count(cardio, "SELECT count(*) FROM patient")).isEqualTo(40);
        assertThat(count(cardio, "SELECT count(*) FROM consultation")).isGreaterThan(10);
        assertThat(count(cardio, "SELECT count(*) FROM prescription")).isGreaterThan(10);
        assertThat(count(cardio, "SELECT count(*) FROM file_attente")).isEqualTo(3);
        assertThat(count(cardio, "SELECT count(*) FROM rendez_vous")).isGreaterThan(5);

        MedsolUser pediatre = (MedsolUser) accounts.loadUserByUsername("medecin.pediatrie@demo.medsol");
        assertThat(count(pediatre, "SELECT count(*) FROM patient")).isEqualTo(40);
        assertThat(count(pediatre, "SELECT count(*) FROM medicament WHERE cabinet_id IS NOT NULL")).isEqualTo(4);

        for (String url : new String[]{"/", "/patients", "/queue", "/planning"}) {
            mvc.perform(get(url).with(user(assistante))).andExpect(status().isOk());
        }
        String pid = text(cardio, "SELECT id::text FROM patient ORDER BY numero LIMIT 1");
        mvc.perform(get("/patients/" + pid + "/dossier").with(user(cardio)).param("onglet", "ordonnances")).andExpect(status().isOk());
    }
}
