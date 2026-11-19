package com.medsol.abonnement;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Abonnement des cabinets. Lecture par le cabinet (connexion soumise à la RLS) ; prolongation, suspension et
 * paiements par la plateforme (administrateur MedSol, Stripe), sur les connexions du propriétaire du schéma.
 * Au-delà de la date de fin, le cabinet passe en lecture seule (ses données restent consultables).
 */
@Service
public class AbonnementService {

    public record Etat(String statut, LocalDate fin, String formule, String formuleNom, String periode, long joursRestants) {
        public boolean expire() { return joursRestants < 0; }
        public boolean suspendu() { return "SUSPENDU".equals(statut); }
        /** Lecture seule : abonnement échu ou suspendu. */
        public boolean lectureSeule() { return expire() || suspendu(); }
        public boolean essai() { return "ESSAI".equals(statut); }
        /** Bandeau d'avertissement : 7 derniers jours, ou lecture seule. */
        public boolean alerte() { return lectureSeule() || joursRestants <= 7; }
    }

    public record Prix(String devise, BigDecimal mensuel, BigDecimal annuel, boolean fondateur) {
        public Prix(String devise, BigDecimal mensuel, BigDecimal annuel) { this(devise, mensuel, annuel, false); }
        public BigDecimal montant(String periode) { return "ANNUEL".equals(periode) ? annuel : mensuel; }
    }

    public record Paiement(UUID id, Instant le, BigDecimal montant, String devise, String periode, String mode, String reference,
                           LocalDate du, LocalDate au, String numero) {}

    private final JdbcTemplate jdbc;
    private final JdbcTemplate plateforme;
    private final TransactionTemplate txPlateforme;

    private final Croissance croissance;

    public AbonnementService(JdbcTemplate jdbc, @Qualifier("plateforme") JdbcTemplate plateforme, Croissance croissance) {
        this.jdbc = jdbc;
        this.plateforme = plateforme;
        this.croissance = croissance;
        this.txPlateforme = new TransactionTemplate(new DataSourceTransactionManager(plateforme.getDataSource()));
    }

    /** État de l'abonnement du cabinet courant (une requête), ou vide hors cabinet. */
    public Optional<Etat> etat(LocalDate aujourdhui) {
        return jdbc.query("""
                SELECT a.statut, a.fin, a.formule_code, coalesce(f.nom->>?, f.nom->>'fr'), a.periode
                FROM abonnement a JOIN formule f ON f.code = a.formule_code""",
                (rs, i) -> {
                    LocalDate fin = rs.getObject(2, LocalDate.class);
                    return new Etat(rs.getString(1), fin, rs.getString(3), rs.getString(4), rs.getString(5),
                            ChronoUnit.DAYS.between(aujourdhui, fin));
                }, LocaleContextHolder.getLocale().getLanguage()).stream().findFirst();
    }

    /** Prix de la formule dans la devise du cabinet (l'euro à défaut). */
    public Prix prix(String formule, String devise) {
        // cabinet « fondateur » : prix MAD garantis à vie (Plateforme › Offre fondateur)
        if ("MAD".equals(devise) && Boolean.TRUE.equals(jdbc.query("SELECT tarif_fondateur FROM cabinet", (rs, i) -> rs.getBoolean(1))
                .stream().findFirst().orElse(false))) {
            Croissance.Fondateur f = croissance.fondateur();
            if (f.mensuel() != null && f.annuel() != null) return new Prix("MAD", f.mensuel(), f.annuel(), true);
        }
        return jdbc.query("""
                SELECT devise, mensuel, annuel FROM formule_prix WHERE formule_code = ? AND devise IN (?, 'EUR')
                ORDER BY devise = ? DESC LIMIT 1""",
                (rs, i) -> new Prix(rs.getString(1), rs.getBigDecimal(2), rs.getBigDecimal(3)), formule, devise, devise)
                .stream().findFirst().orElseThrow();
    }

    private static final String PAIEMENT = "SELECT id, cree_le, montant, devise, periode, mode, reference, du, au, numero FROM paiement_abonnement ";

    private static Paiement paiement(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Paiement(rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant(), rs.getBigDecimal(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getObject(8, LocalDate.class), rs.getObject(9, LocalDate.class),
                rs.getString(10));
    }

    public List<Paiement> paiements() {
        return jdbc.query(PAIEMENT + "ORDER BY cree_le DESC", AbonnementService::paiement);
    }

    /** Un paiement du cabinet courant (la RLS écarte ceux des autres cabinets). */
    public Optional<Paiement> paiement(UUID id) {
        return jdbc.query(PAIEMENT + "WHERE id = ?", AbonnementService::paiement, id).stream().findFirst();
    }

    // ── Plateforme (administrateur MedSol, Stripe) ──────────────────────

    /**
     * Enregistre un paiement et prolonge l'abonnement d'un mois ou d'un an, à partir de la fin actuelle (ou
     * d'aujourd'hui si elle est passée). Idempotent par référence (un rappel Stripe rejoué ne prolonge qu'une fois).
     *
     * @return la nouvelle date de fin, ou vide si cette référence était déjà enregistrée
     */
    public Optional<LocalDate> prolonger(UUID cabinetId, String periode, BigDecimal montant, String devise, String mode,
                                         String reference, String par, LocalDate aujourdhui) {
        try {
            return txPlateforme.execute(t -> {
                LocalDate fin = plateforme.queryForObject("SELECT fin FROM abonnement WHERE cabinet_id = ? FOR UPDATE", LocalDate.class, cabinetId);
                LocalDate du = fin == null || fin.isBefore(aujourdhui) ? aujourdhui : fin.plusDays(1);
                LocalDate au = ("ANNUEL".equals(periode) ? du.plusYears(1) : du.plusMonths(1)).minusDays(1);
                // numéro de facture annuel sans trou : annulé avec la transaction si le paiement est un doublon
                Integer n = plateforme.queryForObject("""
                        INSERT INTO plateforme_compteur (annee, valeur) VALUES (?, 1)
                        ON CONFLICT (annee) DO UPDATE SET valeur = plateforme_compteur.valeur + 1 RETURNING valeur""",
                        Integer.class, aujourdhui.getYear());
                String numero = String.format("MS-%d-%04d", aujourdhui.getYear(), n);
                plateforme.update("""
                        INSERT INTO paiement_abonnement (cabinet_id, montant, devise, periode, mode, reference, du, au, cree_par, numero)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                        cabinetId, montant, devise, periode, mode, reference == null || reference.isBlank() ? null : reference.strip(),
                        Date.valueOf(du), Date.valueOf(au), par, numero);
                plateforme.update("UPDATE abonnement SET statut = 'ACTIF', fin = ?, periode = ?, modifie_le = now() WHERE cabinet_id = ?",
                        Date.valueOf(au), periode, cabinetId);
                // premier paiement d'un cabinet parrainé : un mois offert au filleul et au parrain
                croissance.recompenser(plateforme, cabinetId);
                return Optional.of(plateforme.queryForObject("SELECT fin FROM abonnement WHERE cabinet_id = ?", LocalDate.class, cabinetId));
            });
        } catch (DuplicateKeyException e) {
            return Optional.empty();
        }
    }

    public void suspendre(UUID cabinetId, boolean suspendu) {
        plateforme.update("""
                UPDATE abonnement SET statut = CASE WHEN ? THEN 'SUSPENDU'
                    WHEN EXISTS (SELECT 1 FROM paiement_abonnement p WHERE p.cabinet_id = abonnement.cabinet_id) THEN 'ACTIF' ELSE 'ESSAI' END,
                    modifie_le = now() WHERE cabinet_id = ?""", suspendu, cabinetId);
    }

    /** Prolonge l'essai (geste commercial). */
    public void prolongerEssai(UUID cabinetId, int jours) {
        plateforme.update("UPDATE abonnement SET fin = greatest(fin, current_date) + ?, modifie_le = now() WHERE cabinet_id = ? AND statut = 'ESSAI'",
                jours, cabinetId);
    }
}
