package com.medsol;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Un seul PostgreSQL embarqué pour toute la suite de tests. */
abstract class AbstractDbTest {

    private static final EmbeddedPostgres PG = start();

    private static EmbeddedPostgres start() {
        try {
            EmbeddedPostgres pg = EmbeddedPostgres.start();
            try (var c = pg.getPostgresDatabase().getConnection()) {
                c.createStatement().execute("create database medsol_db");
            }
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { pg.close(); } catch (Exception ignored) { }
            }));
            return pg;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        r.add("medsol.db.url", () -> "jdbc:postgresql://localhost:" + PG.getPort() + "/medsol_db");
        r.add("medsol.db.user", () -> "postgres");
        r.add("medsol.db.password", () -> "postgres");
        r.add("medsol.encryption-key", () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        r.add("medsol.taches", () -> "false");   // tâches planifiées appelées directement par les tests
    }
}
