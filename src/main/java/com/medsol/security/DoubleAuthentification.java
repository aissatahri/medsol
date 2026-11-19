package com.medsol.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Double authentification d'un compte : clé TOTP chiffrée en base, code à 6 chiffres accepté une seule fois
 * (anti-rejeu), 10 codes de secours à usage unique (téléphone perdu ou changé). Connexions du propriétaire : appelée
 * pour le compte connecté, ou après une vérification d'appartenance au cabinet (Paramètres › Utilisateurs) ou par
 * l'administrateur de la plateforme.
 */
@Service
public class DoubleAuthentification implements ApplicationRunner {

    public enum Resultat { CODE, SECOURS, REFUS }

    public static final int NOMBRE_CODES = 10;
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";   // sans 0/O ni 1/I : dictés sans erreur
    private static final SecureRandom HASARD = new SecureRandom();
    private static final Logger LOG = LoggerFactory.getLogger(DoubleAuthentification.class);

    private final JdbcTemplate plateforme;
    private final Chiffrement chiffrement;
    private final TransactionTemplate tx;

    public DoubleAuthentification(@Qualifier("plateforme") JdbcTemplate plateforme, Chiffrement chiffrement) {
        this.plateforme = plateforme;
        this.chiffrement = chiffrement;
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(plateforme.getDataSource()));
    }

    private static String contexte(UUID compte) { return "totp:" + compte; }

    /** Active la double authentification ; {@code pas} : celui du code de confirmation, qui ne pourra pas resservir. */
    public List<String> activer(UUID compte, String secret, long pas) {
        return tx.execute(s -> {
            plateforme.update("UPDATE utilisateur SET totp_secret = ?, totp_actif = true, totp_dernier_pas = ? WHERE id = ?",
                    chiffrement.chiffrer(secret, contexte(compte)), pas, compte);
            return nouveauxCodes(compte);
        });
    }

    /** Nouveaux codes de secours (les anciens ne valent plus rien). @return les codes en clair, à montrer une fois. */
    public List<String> nouveauxCodes(UUID compte) {
        return tx.execute(s -> {
            plateforme.update("DELETE FROM code_secours WHERE utilisateur_id = ?", compte);
            List<String> codes = new ArrayList<>();
            for (int i = 0; i < NOMBRE_CODES; i++) {
                StringBuilder c = new StringBuilder();
                for (int k = 0; k < 10; k++) {
                    if (k == 5) c.append('-');
                    c.append(ALPHABET.charAt(HASARD.nextInt(ALPHABET.length())));
                }
                codes.add(c.toString());
                plateforme.update("INSERT INTO code_secours (utilisateur_id, empreinte) VALUES (?, ?)", compte, empreinte(c.toString()));
            }
            return codes;
        });
    }

    public long codesRestants(UUID compte) {
        Long n = plateforme.queryForObject("SELECT count(*) FROM code_secours WHERE utilisateur_id = ? AND utilise_le IS NULL", Long.class, compte);
        return n == null ? 0 : n;
    }

    /** Code à 6 chiffres de l'application, ou code de secours ; chacun n'est accepté qu'une fois. */
    public Resultat verifier(UUID compte, String saisie, Instant maintenant) {
        if (saisie == null) return Resultat.REFUS;
        String s = saisie.replaceAll("[\\s-]", "").toUpperCase();
        if (s.matches("\\d{6}")) {
            String secret = plateforme.query("SELECT totp_secret FROM utilisateur WHERE id = ? AND totp_actif",
                    (rs, i) -> rs.getString(1), compte).stream().findFirst().orElse(null);
            if (secret == null) return Resultat.REFUS;
            OptionalLong pas = Totp.pas(chiffrement.dechiffrer(secret, contexte(compte)), s, maintenant);
            // mise à jour conditionnelle : deux requêtes simultanées avec le même code ne passent pas toutes les deux
            if (pas.isPresent() && plateforme.update("UPDATE utilisateur SET totp_dernier_pas = ? WHERE id = ? AND totp_dernier_pas < ?",
                    pas.getAsLong(), compte, pas.getAsLong()) == 1) {
                return Resultat.CODE;
            }
            return Resultat.REFUS;
        }
        if (s.length() == 10 && plateforme.update("""
                UPDATE code_secours SET utilise_le = now() WHERE utilisateur_id = ? AND empreinte = ? AND utilise_le IS NULL""",
                compte, empreinte(s)) == 1) {
            return Resultat.SECOURS;
        }
        return Resultat.REFUS;
    }

    /** Retire la double authentification ; {@code fermerSessions} : déconnecte aussi tous ses appareils. */
    public void retirer(UUID compte, boolean fermerSessions) {
        tx.executeWithoutResult(s -> {
            plateforme.update("UPDATE utilisateur SET totp_secret = NULL, totp_actif = false, totp_dernier_pas = 0"
                    + (fermerSessions ? ", version_session = version_session + 1" : "") + " WHERE id = ?", compte);
            plateforme.update("DELETE FROM code_secours WHERE utilisateur_id = ?", compte);
        });
    }

    private static String empreinte(String code) {
        try {
            String normal = code.replaceAll("[\\s-]", "").toUpperCase();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(normal.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Au démarrage : les clés enregistrées en clair (avant le chiffrement) sont chiffrées. */
    @Override
    public void run(ApplicationArguments args) {
        List<Object[]> clairs = plateforme.query("SELECT id, totp_secret FROM utilisateur WHERE totp_secret IS NOT NULL AND totp_secret NOT LIKE 'v1:%'",
                (rs, i) -> new Object[]{rs.getObject(1, UUID.class), rs.getString(2)});
        for (Object[] c : clairs) {
            UUID id = (UUID) c[0];
            plateforme.update("UPDATE utilisateur SET totp_secret = ? WHERE id = ?", chiffrement.chiffrer((String) c[1], contexte(id)), id);
        }
        if (!clairs.isEmpty()) LOG.info("{} clé(s) de double authentification chiffrée(s)", clairs.size());
    }
}
