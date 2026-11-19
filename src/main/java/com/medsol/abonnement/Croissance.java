package com.medsol.abonnement;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Parrainage entre confrères et offre « fondateur ». Lit plusieurs cabinets (parrain, filleuls, places restantes) :
 * connexions du propriétaire, en ne retournant que le strict nécessaire (nom du cabinet, dates).
 */
@Service
public class Croissance {

    public record Parrain(UUID id, String nom) {}

    public record Filleul(String nom, Instant inscritLe, boolean recompense) {}

    /** Offre fondateur : prix MAD garantis à vie pour les premiers cabinets inscrits pendant l'offre. */
    public record Fondateur(boolean actif, BigDecimal mensuel, BigDecimal annuel, int places, int prises) {
        public int restantes() { return Math.max(0, places - prises); }
        public boolean ouverte() { return actif && mensuel != null && annuel != null && restantes() > 0; }
    }

    private final JdbcTemplate plateforme;

    public Croissance(@Qualifier("plateforme") JdbcTemplate plateforme) {
        this.plateforme = plateforme;
    }

    // ── Parrainage ──────────────────────────────────────────────────────

    public Optional<Parrain> parrain(String code) {
        if (code == null || !code.strip().matches("[A-Za-z0-9]{4,20}")) return Optional.empty();
        return plateforme.query("SELECT id, nom FROM cabinet WHERE code_parrainage = upper(?) AND NOT interne",
                (rs, i) -> new Parrain(rs.getObject(1, UUID.class), rs.getString(2)), code.strip()).stream().findFirst();
    }

    /** Après l'inscription : rattachement au parrain, place « fondateur » si l'offre est ouverte. */
    public void apresInscription(UUID cabinet, String codeParrain) {
        parrain(codeParrain).filter(p -> !p.id().equals(cabinet))
                .ifPresent(p -> plateforme.update("UPDATE cabinet SET parrain_id = ? WHERE id = ?", p.id(), cabinet));
        if (fondateur().ouverte()) {
            plateforme.update("UPDATE cabinet SET tarif_fondateur = true WHERE id = ?", cabinet);
        }
    }

    public String code(UUID cabinet) {
        return plateforme.queryForObject("SELECT code_parrainage FROM cabinet WHERE id = ?", String.class, cabinet);
    }

    public List<Filleul> filleuls(UUID parrain) {
        return plateforme.query("""
                SELECT nom, cree_le, parrainage_recompense_le IS NOT NULL FROM cabinet WHERE parrain_id = ? ORDER BY cree_le DESC""",
                (rs, i) -> new Filleul(rs.getString(1), rs.getTimestamp(2).toInstant(), rs.getBoolean(3)), parrain);
    }

    /**
     * Premier paiement d'un filleul : un mois offert au filleul et à son parrain (une seule fois). Appelé dans la
     * transaction de l'enregistrement du paiement.
     */
    void recompenser(JdbcTemplate tx, UUID filleul) {
        UUID parrain = tx.query("""
                SELECT parrain_id FROM cabinet WHERE id = ? AND parrain_id IS NOT NULL AND parrainage_recompense_le IS NULL
                  AND (SELECT count(*) FROM paiement_abonnement p WHERE p.cabinet_id = cabinet.id) = 1""",
                (rs, i) -> rs.getObject(1, UUID.class), filleul).stream().findFirst().orElse(null);
        if (parrain == null) return;
        tx.update("UPDATE abonnement SET fin = fin + interval '1 month', modifie_le = now() WHERE cabinet_id = ?", filleul);
        tx.update("""
                UPDATE abonnement SET fin = greatest(fin, current_date) + interval '1 month', modifie_le = now()
                WHERE cabinet_id = ?""", parrain);
        tx.update("UPDATE cabinet SET parrainage_recompense_le = now() WHERE id = ?", filleul);
    }

    // ── Offre fondateur ─────────────────────────────────────────────────

    public Fondateur fondateur() {
        int prises = plateforme.queryForObject("SELECT count(*) FROM cabinet WHERE tarif_fondateur AND NOT interne", Integer.class);
        return new Fondateur("true".equals(Editeur.reglage(plateforme, "fondateur_actif")),
                nombre(Editeur.reglage(plateforme, "fondateur_mensuel")), nombre(Editeur.reglage(plateforme, "fondateur_annuel")),
                entier(Editeur.reglage(plateforme, "fondateur_places")), prises);
    }

    public void reglerFondateur(boolean actif, BigDecimal mensuel, BigDecimal annuel, int places) {
        Editeur.ecrire(plateforme, "fondateur_actif", String.valueOf(actif));
        Editeur.ecrire(plateforme, "fondateur_mensuel", mensuel == null ? "" : mensuel.toPlainString());
        Editeur.ecrire(plateforme, "fondateur_annuel", annuel == null ? "" : annuel.toPlainString());
        Editeur.ecrire(plateforme, "fondateur_places", String.valueOf(Math.max(0, places)));
    }

    private static BigDecimal nombre(String s) {
        try { return s == null || s.isBlank() ? null : new BigDecimal(s); } catch (NumberFormatException e) { return null; }
    }

    private static int entier(String s) {
        try { return s == null || s.isBlank() ? 0 : Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }
}
