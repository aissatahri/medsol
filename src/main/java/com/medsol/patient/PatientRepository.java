package com.medsol.patient;

import com.medsol.cabinet.CabinetService;
import com.medsol.security.MedsolUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PatientRepository {

    public record Page(List<Patient> items, long total, int page, int size) {
        public int pages() { return (int) Math.max(1, Math.ceil(total / (double) size)); }
        public boolean hasPrev() { return page > 0; }
        public boolean hasNext() { return page + 1 < pages(); }
    }

    private static final String COLS = "id, numero, nom, prenom, date_naissance, sexe, telephone, email, identifiant_national, adresse, "
            + "situation_familiale, profession, couverture, traitement_avant";

    private static final RowMapper<Patient> MAPPER = (rs, i) -> new Patient(
            rs.getObject("id", UUID.class), rs.getLong("numero"), rs.getString("nom"), rs.getString("prenom"),
            rs.getObject("date_naissance", java.time.LocalDate.class), rs.getString("sexe"), rs.getString("telephone"),
            rs.getString("email"), rs.getString("identifiant_national"), rs.getString("adresse"),
            rs.getString("situation_familiale"), rs.getString("profession"), rs.getString("couverture"), rs.getString("traitement_avant"));

    private final JdbcTemplate jdbc;
    private final CabinetService cabinets;

    public PatientRepository(JdbcTemplate jdbc, CabinetService cabinets) {
        this.jdbc = jdbc;
        this.cabinets = cabinets;
    }

    /** Recherche tolérante (nom, prénom, téléphone, n° de dossier, identifiant national), paginée. */
    public Page search(String q, int page, int size) {
        String term = q == null ? "" : q.trim().toLowerCase();
        String like = "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        String where = term.isEmpty() ? "NOT archive"
                : """
                  NOT archive AND (lower(nom || ' ' || prenom) LIKE ? OR lower(prenom || ' ' || nom) LIKE ?
                       OR telephone LIKE ? OR lower(identifiant_national) LIKE ? OR numero::text = ?)""";
        Object[] args = term.isEmpty() ? new Object[0] : new Object[]{like, like, like, like, term};
        Long total = jdbc.queryForObject("SELECT count(*) FROM patient WHERE " + where, Long.class, args);
        Object[] pageArgs = java.util.Arrays.copyOf(args, args.length + 2);
        pageArgs[args.length] = size;
        pageArgs[args.length + 1] = (long) page * size;
        List<Patient> items = jdbc.query("SELECT " + COLS + " FROM patient WHERE " + where
                + " ORDER BY lower(nom), lower(prenom), numero LIMIT ? OFFSET ?", MAPPER, pageArgs);
        return new Page(items, total == null ? 0 : total, page, size);
    }

    public Optional<Patient> find(UUID id) {
        return jdbc.query("SELECT " + COLS + " FROM patient WHERE id = ? AND NOT archive", MAPPER, id).stream().findFirst();
    }

    /**
     * Patients qui sont peut-être la même personne (avertissement du bureau) : même nom et prénom avec une date de
     * naissance compatible, ou même identifiant national.
     */
    public List<Patient> semblables(PatientForm f, UUID except) {
        return jdbc.query("SELECT " + COLS + """
                 FROM patient WHERE NOT archive AND (?::uuid IS NULL OR id <> ?::uuid) AND (
                   (lower(nom) = lower(?) AND lower(prenom) = lower(?)
                    AND (date_naissance IS NULL OR ?::date IS NULL OR date_naissance = ?::date))
                   OR (identifiant_national IS NOT NULL AND lower(identifiant_national) = lower(?)))
                ORDER BY numero LIMIT 5""", MAPPER, except, except, f.getNom().trim(), f.getPrenom().trim(),
                date(f), date(f), blank(f.getIdentifiantNational()));
    }

    public boolean duplicateExists(PatientForm f, UUID except) { return !semblables(f, except).isEmpty(); }

    /**
     * Couvertures sanitaires proposées à la saisie : la liste du pays, celles ajoutées par le cabinet (Paramètres ›
     * Cabinet), puis celles déjà tapées sur des fiches. La saisie reste libre.
     */
    public List<String> couvertures() {
        return jdbc.queryForList("""
                SELECT libelle FROM (
                    SELECT libelle, CASE WHEN cabinet_id IS NULL THEN 0 ELSE 1 END AS rang, id::text AS ordre
                    FROM couverture_sanitaire WHERE pays = (SELECT pays FROM cabinet)
                    UNION ALL
                    SELECT DISTINCT couverture, 2, couverture FROM patient WHERE coalesce(couverture, '') <> ''
                ) x
                GROUP BY libelle ORDER BY min(rang), min(lpad(ordre, 20, '0'))""", String.class);
    }

    public record Couverture(long id, String libelle) {}

    /** Couvertures ajoutées par le cabinet (modifiables), distinctes de la liste commune du pays. */
    public List<Couverture> couverturesDuCabinet() {
        return jdbc.query("SELECT id, libelle FROM couverture_sanitaire WHERE cabinet_id = current_cabinet() ORDER BY lower(libelle)",
                (rs, i) -> new Couverture(rs.getLong(1), rs.getString(2)));
    }

    public List<String> couverturesCommunes() {
        return jdbc.queryForList("SELECT libelle FROM couverture_sanitaire WHERE cabinet_id IS NULL AND pays = (SELECT pays FROM cabinet) ORDER BY id", String.class);
    }

    /** @return false si elle existe déjà (dans la liste commune ou celle du cabinet) */
    public boolean ajouterCouverture(String libelle) {
        String l = libelle.strip();
        Boolean existe = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM couverture_sanitaire WHERE pays = (SELECT pays FROM cabinet) AND lower(libelle) = lower(?))""",
                Boolean.class, l);
        if (Boolean.TRUE.equals(existe)) return false;
        jdbc.update("INSERT INTO couverture_sanitaire (pays, libelle, cabinet_id) SELECT pays, ?, id FROM cabinet", l);
        return true;
    }

    public void supprimerCouverture(long id) {
        jdbc.update("DELETE FROM couverture_sanitaire WHERE id = ? AND cabinet_id = current_cabinet()", id);
    }

    public UUID create(PatientForm f, MedsolUser user) {
        long numero = cabinets.nextValue("patient");
        return jdbc.queryForObject("""
                INSERT INTO patient (cabinet_id, numero, nom, prenom, date_naissance, sexe, telephone, email,
                                     identifiant_national, adresse, situation_familiale, profession, couverture,
                                     traitement_avant, consentement_le, cree_par)
                VALUES (current_cabinet(), ?, ?, ?, ?, nullif(?, ''), ?, ?, ?, ?, nullif(?, ''), ?, ?, ?, CASE WHEN ? THEN now() END, ?)
                RETURNING id""", UUID.class,
                numero, f.getNom().trim(), f.getPrenom().trim(), date(f), f.getSexe(),
                blank(f.getTelephone()), f.getEmail(), blank(f.getIdentifiantNational()), blank(f.getAdresse()),
                f.getSituationFamiliale(), blank(f.getProfession()), blank(f.getCouverture()),
                "MEDECIN".equals(user.role()) ? blank(f.getTraitementAvant()) : null,
                f.isConsentement(), user.id());
    }

    /** Le traitement en cours n'est modifié que par le médecin. */
    public void update(UUID id, PatientForm f, boolean medecin) {
        jdbc.update("""
                UPDATE patient SET nom = ?, prenom = ?, date_naissance = ?, sexe = nullif(?, ''), telephone = ?, email = ?,
                       identifiant_national = ?, adresse = ?, situation_familiale = nullif(?, ''), profession = ?, couverture = ?,
                       traitement_avant = CASE WHEN ? THEN ? ELSE traitement_avant END, modifie_le = now()
                WHERE id = ? AND NOT archive""",
                f.getNom().trim(), f.getPrenom().trim(), date(f), f.getSexe(),
                blank(f.getTelephone()), f.getEmail(), blank(f.getIdentifiantNational()), blank(f.getAdresse()),
                f.getSituationFamiliale(), blank(f.getProfession()), blank(f.getCouverture()),
                medecin, blank(f.getTraitementAvant()), id);
    }

    private static Date date(PatientForm f) { return f.getDateNaissance() == null ? null : Date.valueOf(f.getDateNaissance()); }

    /** Suppression logique : le dossier reste conservé (obligations légales de conservation). */
    public record Archive(UUID id, long numero, String nomComplet, java.time.LocalDate dateNaissance, String telephone, java.time.Instant archiveLe) {}

    /** Dossiers archivés du cabinet (retirés des listes, conservés), du plus récent au plus ancien. */
    public List<Archive> archives() {
        return jdbc.query("""
                SELECT id, numero, upper(nom) || ' ' || prenom, date_naissance, telephone, modifie_le
                FROM patient WHERE archive ORDER BY modifie_le DESC LIMIT 500""",
                (rs, i) -> new Archive(rs.getObject(1, UUID.class), rs.getLong(2), rs.getString(3), rs.getObject(4, java.time.LocalDate.class),
                        rs.getString(5), rs.getTimestamp(6).toInstant()));
    }

    /** Remet un dossier archivé dans les listes ; false s'il n'était pas archivé (ou d'un autre cabinet). */
    public boolean restaurer(UUID id) {
        return jdbc.update("UPDATE patient SET archive = false, modifie_le = now() WHERE id = ? AND archive", id) == 1;
    }

    public void archive(UUID id) {
        jdbc.update("UPDATE patient SET archive = true, modifie_le = now() WHERE id = ?", id);
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
