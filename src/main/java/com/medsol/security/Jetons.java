package com.medsol.security;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Jetons à usage unique (mot de passe oublié : 30 min, invitation : 72 h). Seule l'empreinte SHA-256 est en base ;
 * consommer un jeton fixe le mot de passe, débloque le compte et ferme ses sessions. Pages publiques (sans cabinet) :
 * connexions du propriétaire du schéma.
 */
@Service
public class Jetons {

    public record Compte(UUID id, String email, String nom, String langue) {}

    private static final SecureRandom HASARD = new SecureRandom();

    private final JdbcTemplate plateforme;
    private final TransactionTemplate tx;

    public Jetons(@Qualifier("plateforme") JdbcTemplate plateforme) {
        this.plateforme = plateforme;
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(plateforme.getDataSource()));
    }

    /** Compte actif portant cet e-mail (mot de passe oublié). */
    public Optional<Compte> compteActif(String email) {
        return plateforme.query("SELECT id, email, nom_complet, langue FROM utilisateur WHERE lower(email) = lower(?) AND actif",
                (rs, i) -> new Compte(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4)), email.strip()).stream().findFirst();
    }

    /** Nouveau jeton (les précédents du même type sont annulés). @return le jeton en clair, à mettre dans le lien. */
    public String creer(UUID utilisateur, String type, int minutes) {
        byte[] b = new byte[32];
        HASARD.nextBytes(b);
        String jeton = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        tx.executeWithoutResult(s -> {
            plateforme.update("UPDATE jeton SET utilise_le = now() WHERE utilisateur_id = ? AND type = ? AND utilise_le IS NULL", utilisateur, type);
            plateforme.update("INSERT INTO jeton (empreinte, utilisateur_id, type, expire_le) VALUES (?, ?, ?, ?)",
                    empreinte(jeton), utilisateur, type, Timestamp.from(Instant.now().plusSeconds(60L * minutes)));
        });
        return jeton;
    }

    /** Jeton encore valable (pour afficher le formulaire). */
    public Optional<Compte> valide(String jeton, String type) {
        if (jeton == null || jeton.length() > 100) return Optional.empty();
        return plateforme.query("""
                SELECT u.id, u.email, u.nom_complet, u.langue FROM jeton j JOIN utilisateur u ON u.id = j.utilisateur_id
                WHERE j.empreinte = ? AND j.type = ? AND j.utilise_le IS NULL AND j.expire_le > now() AND u.actif""",
                (rs, i) -> new Compte(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4)), empreinte(jeton), type).stream().findFirst();
    }

    /** Consomme le jeton et fixe le mot de passe (déjà chiffré). @return le compte, ou vide si le jeton n'est plus valable. */
    public Optional<Compte> consommer(String jeton, String type, String motDePasseChiffre) {
        return tx.execute(s -> {
            List<UUID> u = plateforme.queryForList("""
                    UPDATE jeton SET utilise_le = now() WHERE empreinte = ? AND type = ? AND utilise_le IS NULL AND expire_le > now()
                    RETURNING utilisateur_id""", UUID.class, empreinte(jeton), type);
            if (u.isEmpty()) return Optional.<Compte>empty();
            plateforme.update("""
                    UPDATE utilisateur SET mot_de_passe = ?, echecs = 0, bloque_jusqua = NULL, version_session = version_session + 1
                    WHERE id = ? AND actif""", motDePasseChiffre, u.get(0));
            return plateforme.query("SELECT id, email, nom_complet, langue FROM utilisateur WHERE id = ?",
                    (rs, i) -> new Compte(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4)), u.get(0)).stream().findFirst();
        });
    }

    /** Comptes ayant une invitation encore valable (pastille « Invitation envoyée »). */
    public java.util.Set<UUID> invitationsEnAttente(java.util.Collection<UUID> comptes) {
        if (comptes.isEmpty()) return java.util.Set.of();
        return new java.util.HashSet<>(plateforme.queryForList("""
                SELECT utilisateur_id FROM jeton WHERE type = 'INVITATION' AND utilise_le IS NULL AND expire_le > now()
                  AND utilisateur_id = ANY (?)""", UUID.class, (Object) comptes.toArray(new UUID[0])));
    }

    /** Inscription au journal d'accès du cabinet du compte (pages publiques : aucun cabinet en cours). */
    public void journal(UUID utilisateur, String action, String ip) {
        plateforme.update("""
                INSERT INTO journal_audit (cabinet_id, utilisateur_id, action, objet, objet_id, ip)
                SELECT cabinet_id, id, ?, 'utilisateur', id::text, ? FROM utilisateur WHERE id = ?""", action, ip, utilisateur);
    }

    static String empreinte(String jeton) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(jeton.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
