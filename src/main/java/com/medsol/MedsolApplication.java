package com.medsol;

import com.medsol.config.DevEmbeddedPostgres;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MedsolApplication {

    public static void main(String[] args) {
        // MEDSOL_EMBEDDED_PG=true : démarre un PostgreSQL embarqué (développement uniquement)
        DevEmbeddedPostgres.startIfRequested();
        SpringApplication.run(MedsolApplication.class, args);
    }
}
