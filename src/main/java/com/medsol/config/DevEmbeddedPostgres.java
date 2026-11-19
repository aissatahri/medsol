package com.medsol.config;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import java.io.IOException;
import java.nio.file.Path;

/** PostgreSQL embarqué pour le développement : aucune installation requise. Jamais en production. */
public final class DevEmbeddedPostgres {

    private DevEmbeddedPostgres() {}

    public static void startIfRequested() {
        if (!"true".equalsIgnoreCase(System.getenv("MEDSOL_EMBEDDED_PG"))) {
            return;
        }
        try {
            Path data = Path.of(System.getProperty("user.home"), ".medsol", "pgdata");
            EmbeddedPostgres pg = EmbeddedPostgres.builder()
                    .setDataDirectory(data)
                    .setCleanDataDirectory(false)
                    .start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { pg.close(); } catch (IOException ignored) { }
            }));
            // La base medsol_db est créée si besoin
            try (var c = pg.getPostgresDatabase().getConnection();
                 var rs = c.createStatement().executeQuery("select 1 from pg_database where datname='medsol_db'")) {
                if (!rs.next()) {
                    c.createStatement().execute("create database medsol_db");
                }
            }
            System.setProperty("medsol.db.url", "jdbc:postgresql://localhost:" + pg.getPort() + "/medsol_db");
            System.setProperty("medsol.db.user", "postgres");
            System.setProperty("medsol.db.password", "postgres");
            // Développement : données de démonstration par défaut (MEDSOL_DEMO=false pour s'en passer)
            if (System.getenv("MEDSOL_DEMO") == null) {
                System.setProperty("medsol.demo", "true");
            }
            if (System.getenv("MEDSOL_PLATFORM_ADMINS") == null) {
                System.setProperty("medsol.platform-admins", "admin@demo.medsol");
            }
            if (System.getenv("MEDSOL_BILLING_INSTRUCTIONS") == null) {
                System.setProperty("medsol.billing.manual-instructions",
                        "Virement : MedSol SARL — IBAN (exemple) MA00 0000 0000 0000 0000 0000 000 — indiquez le nom du cabinet en référence.");
            }
            // clé de chiffrement de développement, créée une fois et gardée à côté des données
            if (System.getenv("MEDSOL_ENCRYPTION_KEY") == null) {
                Path cle = Path.of(System.getProperty("user.home"), ".medsol", "cle-chiffrement-dev");
                if (!java.nio.file.Files.exists(cle)) {
                    byte[] b = new byte[32];
                    new java.security.SecureRandom().nextBytes(b);
                    java.nio.file.Files.writeString(cle, java.util.Base64.getEncoder().encodeToString(b));
                }
                System.setProperty("medsol.encryption-key", java.nio.file.Files.readString(cle).strip());
            }
            System.out.println("PostgreSQL embarqué démarré sur le port " + pg.getPort() + " (données : " + data + ")");
        } catch (Exception e) {
            throw new IllegalStateException("Impossible de démarrer PostgreSQL embarqué", e);
        }
    }
}
