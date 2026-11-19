package com.medsol.caisse;

import com.medsol.cabinet.CabinetService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.*;

/**
 * Actes et versements, avec les règles du bureau (repris de CaisseRepository de SGCM-Cardio) : le total d'une
 * consultation est la somme des actes « payés » (un acte offert compte 0) ; un versement ne dépasse jamais le reste
 * dû, relu en base sous verrou au moment d'encaisser (deux postes peuvent encaisser en même temps).
 */
@Repository
public class CaisseRepository {

    public static final Set<String> MODES = new LinkedHashSet<>(List.of("ESPECES", "CARTE", "CHEQUE", "VIREMENT", "MOBILE"));
    private static final BigDecimal CENTIME = new BigDecimal("0.005");

    public record Acte(UUID id, String nom, BigDecimal prix, boolean actif) {}

    public record ActeRealise(UUID id, UUID acteId, String nom, BigDecimal prix, boolean paye) {}

    public record Versement(UUID id, long numero, BigDecimal montant, LocalDate jour, String mode) {
        public String modeLibelle() { return CaisseRepository.modeLibelle(mode); }
    }

    public record Ligne(UUID consultationId, LocalDate date, UUID patientId, String nom, String prenom, long dossier,
                        BigDecimal total, BigDecimal verse) {
        public BigDecimal reste() { return total.subtract(verse).max(BigDecimal.ZERO); }

        public String etat() {
            if (total.compareTo(CENTIME) <= 0) return "Sans acte payant";
            return reste().compareTo(CENTIME) <= 0 ? "Payé" : verse.compareTo(CENTIME) > 0 ? "Partiel" : "À payer";
        }

        /** État pour les écrans (clé cash.state.*) : SANS_ACTE, PAYE, PARTIEL, A_PAYER. */
        public String etatCode() {
            if (total.compareTo(CENTIME) <= 0) return "SANS_ACTE";
            return reste().compareTo(CENTIME) <= 0 ? "PAYE" : verse.compareTo(CENTIME) > 0 ? "PARTIEL" : "A_PAYER";
        }

        public String nomComplet() { return ((nom == null ? "" : nom.toUpperCase()) + " " + (prenom == null ? "" : prenom)).trim(); }
    }

    public record TotalMode(String mode, BigDecimal montant) {
        public String modeLibelle() { return CaisseRepository.modeLibelle(mode); }
    }

    public record VersementJour(String patient, long dossier, String mode, BigDecimal montant) {}

    public static String modeLibelle(String mode) {
        return switch (mode == null ? "" : mode) {
            case "ESPECES" -> "Espèces";
            case "CARTE" -> "Carte";
            case "CHEQUE" -> "Chèque";
            case "VIREMENT" -> "Virement";
            case "MOBILE" -> "Paiement mobile";
            default -> "";
        };
    }

    private final JdbcTemplate jdbc;
    private final CabinetService cabinets;
    private final TransactionTemplate tx;

    public CaisseRepository(JdbcTemplate jdbc, CabinetService cabinets, PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.cabinets = cabinets;
        this.tx = new TransactionTemplate(tm);
    }

    // ── Catalogue (Paramètres › Actes) ──────────────────────────────────

    public List<Acte> catalogue(boolean aussiInactifs) {
        return jdbc.query("SELECT id, nom, prix, actif FROM acte" + (aussiInactifs ? "" : " WHERE actif") + " ORDER BY lower(nom)",
                (rs, i) -> new Acte(rs.getObject(1, UUID.class), rs.getString(2), rs.getBigDecimal(3), rs.getBoolean(4)));
    }

    public boolean ajouterActe(String nom, BigDecimal prix) {
        try {
            jdbc.update("INSERT INTO acte (cabinet_id, nom, prix) VALUES (current_cabinet(), ?, ?)", nom.strip(), Monnaie.arrondi(prix));
            return true;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return false;
        }
    }

    public void modifierActe(UUID id, BigDecimal prix, boolean actif) {
        jdbc.update("UPDATE acte SET prix = ?, actif = ? WHERE id = ?", Monnaie.arrondi(prix), actif, id);
    }

    // ── Actes d'une consultation ────────────────────────────────────────

    public List<ActeRealise> actes(UUID consultationId) {
        return jdbc.query("""
                SELECT ca.id, ca.acte_id, a.nom, ca.prix, ca.paye FROM consultation_acte ca JOIN acte a ON a.id = ca.acte_id
                WHERE ca.consultation_id = ? ORDER BY lower(a.nom)""",
                (rs, i) -> new ActeRealise(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getBigDecimal(4), rs.getBoolean(5)), consultationId);
    }

    /** Actes réalisés : {@code choix} = acte → payé (vrai) ou offert (faux) ; les actes absents sont retirés. */
    public void enregistrerActes(UUID consultationId, Map<UUID, Boolean> choix) {
        tx.executeWithoutResult(t -> {
            Map<UUID, ActeRealise> existants = new HashMap<>();
            actes(consultationId).forEach(a -> existants.put(a.acteId(), a));
            choix.forEach((acte, paye) -> {
                ActeRealise ex = existants.remove(acte);
                if (ex == null) {
                    jdbc.update("""
                            INSERT INTO consultation_acte (cabinet_id, consultation_id, acte_id, prix, paye)
                            SELECT current_cabinet(), ?, a.id, a.prix, ? FROM acte a WHERE a.id = ?""", consultationId, paye, acte);
                } else if (ex.paye() != paye) {
                    jdbc.update("UPDATE consultation_acte SET paye = ? WHERE id = ?", paye, ex.id());
                }
            });
            existants.values().forEach(a -> jdbc.update("DELETE FROM consultation_acte WHERE id = ?", a.id()));
        });
    }

    /** Total payant de la consultation (actes non offerts), lu en base. */
    public BigDecimal total(UUID consultationId) {
        return Monnaie.arrondi(jdbc.queryForObject(
                "SELECT coalesce(sum(prix), 0) FROM consultation_acte WHERE consultation_id = ? AND paye", BigDecimal.class, consultationId));
    }

    public List<Versement> versements(UUID consultationId) {
        return jdbc.query("SELECT id, numero, montant, jour, mode FROM versement WHERE consultation_id = ? AND annule_le IS NULL ORDER BY cree_le",
                (rs, i) -> new Versement(rs.getObject(1, UUID.class), rs.getLong(2), rs.getBigDecimal(3), rs.getObject(4, LocalDate.class),
                        rs.getString(5)), consultationId);
    }

    public BigDecimal verse(UUID consultationId) {
        return versements(consultationId).stream().map(Versement::montant).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Encaisse un versement (règles de PaiementController du bureau), sous verrou de la consultation.
     *
     * @return null si enregistré, sinon le message d'erreur
     */
    public String encaisser(UUID consultationId, BigDecimal montant, String mode, LocalDate jour, UUID par) {
        String devise = cabinets.current().devise();
        return tx.execute(t -> {
            if (jdbc.queryForList("SELECT id FROM consultation WHERE id = ? AND supprimee_le IS NULL FOR UPDATE", UUID.class, consultationId).isEmpty()) {
                return "msg.cash.consultationGone";   // clé de traduction (CaisseController)
            }
            BigDecimal du = total(consultationId).subtract(verse(consultationId));
            if (montant.compareTo(CENTIME) <= 0) return "msg.cash.amountRequired";
            if (montant.compareTo(du.add(CENTIME)) > 0) {
                return "msg.cash.tooMuch|" + Monnaie.format(du.max(BigDecimal.ZERO), devise);   // clé|paramètre
            }
            long numero = cabinets.nextValue("recu");
            jdbc.update("""
                    INSERT INTO versement (cabinet_id, consultation_id, numero, montant, mode, jour, cree_par)
                    VALUES (current_cabinet(), ?, ?, ?, ?, ?, ?)""",
                    consultationId, numero, Monnaie.arrondi(montant), mode, Date.valueOf(jour), par);
            return null;
        });
    }

    /** Annule un versement saisi par erreur : il reste visible dans la base (trace), plus dans les totaux. */
    public boolean annuler(UUID consultationId, UUID versementId, UUID par) {
        return jdbc.update("UPDATE versement SET annule_le = now(), annule_par = ? WHERE id = ? AND consultation_id = ? AND annule_le IS NULL",
                par, versementId, consultationId) == 1;
    }

    // ── Caisse ──────────────────────────────────────────────────────────

    private static final String LIGNES = """
            SELECT c.id, c.date_consult, p.id AS patient_id, p.nom, p.prenom, p.numero,
                   (SELECT coalesce(sum(ca.prix), 0) FROM consultation_acte ca WHERE ca.consultation_id = c.id AND ca.paye) AS total,
                   (SELECT coalesce(sum(v.montant), 0) FROM versement v WHERE v.consultation_id = c.id AND v.annule_le IS NULL) AS verse
            FROM consultation c JOIN patient p ON p.id = c.patient_id WHERE c.supprimee_le IS NULL
            """;

    private static Ligne ligne(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Ligne(rs.getObject("id", UUID.class), rs.getObject("date_consult", LocalDate.class), rs.getObject("patient_id", UUID.class),
                rs.getString("nom"), rs.getString("prenom"), rs.getLong("numero"),
                Monnaie.arrondi(rs.getBigDecimal("total")), Monnaie.arrondi(rs.getBigDecimal("verse")));
    }

    /** Consultations du jour avec total et versé, en une requête. */
    public List<Ligne> jour(LocalDate jour) {
        return jdbc.query(LIGNES + " AND c.date_consult = ? ORDER BY c.cree_le", (rs, i) -> ligne(rs), Date.valueOf(jour));
    }

    /** Consultations d'un patient (onglet Paiement du dossier). */
    public List<Ligne> patient(UUID patientId) {
        return jdbc.query(LIGNES + " AND c.patient_id = ? ORDER BY c.date_consult", (rs, i) -> ligne(rs), patientId);
    }

    /** Consultations avec un reste à payer, toutes dates (les plus récentes d'abord). */
    public List<Ligne> impayes() {
        return jdbc.query("SELECT * FROM (" + LIGNES + ") x WHERE x.total - x.verse > 0.005 ORDER BY x.date_consult DESC LIMIT 200",
                (rs, i) -> ligne(rs));
    }

    /** Versements de plusieurs consultations en une requête : consultation → lignes. */
    public Map<UUID, List<Versement>> versements(List<UUID> consultations) {
        Map<UUID, List<Versement>> m = new HashMap<>();
        if (consultations.isEmpty()) return m;
        jdbc.query("SELECT consultation_id, id, numero, montant, jour, mode FROM versement WHERE annule_le IS NULL AND consultation_id = ANY (?) ORDER BY cree_le",
                rs -> {
                    m.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>()).add(new Versement(rs.getObject(2, UUID.class),
                            rs.getLong(3), rs.getBigDecimal(4), rs.getObject(5, LocalDate.class), rs.getString(6)));
                }, (Object) consultations.toArray(UUID[]::new));
        return m;
    }

    public List<TotalMode> encaisseLe(LocalDate jour) {
        return jdbc.query("SELECT mode, sum(montant) FROM versement WHERE jour = ? AND annule_le IS NULL GROUP BY mode ORDER BY mode",
                (rs, i) -> new TotalMode(rs.getString(1), Monnaie.arrondi(rs.getBigDecimal(2))), Date.valueOf(jour));
    }

    /** Versements d'un jour pour la clôture de caisse. */
    public List<VersementJour> versementsDuJour(LocalDate jour) {
        return jdbc.query("""
                SELECT upper(p.nom) || ' ' || p.prenom, p.numero, v.mode, v.montant FROM versement v
                JOIN consultation c ON c.id = v.consultation_id JOIN patient p ON p.id = c.patient_id
                WHERE v.jour = ? AND v.annule_le IS NULL ORDER BY v.cree_le""",
                (rs, i) -> new VersementJour(rs.getString(1), rs.getLong(2), modeLibelle(rs.getString(3)), rs.getBigDecimal(4)), Date.valueOf(jour));
    }
}
