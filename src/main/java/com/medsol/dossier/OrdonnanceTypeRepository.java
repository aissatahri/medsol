package com.medsol.dossier;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/**
 * Ordonnances types (repris de SGCM-Cardio) : ordonnances enregistrées sous un nom (« HTA débutante »…) pour les
 * reprendre en un clic ; l'appliquer ajoute les lignes cochées, sauf un médicament déjà prescrit.
 */
@Repository
public class OrdonnanceTypeRepository {

    public record LigneType(UUID id, long medicamentId, String medicament, String dci, String forme, String dose,
                            String duree, String remarque) {
        public String libelle() {
            String nom = medicament == null ? "?" : medicament;
            return forme == null || forme.isBlank() ? nom : nom + " " + forme;
        }

        public String details() {
            StringBuilder b = new StringBuilder();
            for (String s : new String[]{dose, duree, remarque}) {
                if (s != null && !s.isBlank()) b.append(b.isEmpty() ? "" : " - ").append(s.strip());
            }
            return b.toString();
        }
    }

    public record Modele(UUID id, String nom, List<LigneType> lignes) {
        /** Les premiers médicaments, pour reconnaître le modèle d'un coup d'œil. */
        public String apercu() {
            List<String> noms = lignes.stream().map(l -> l.medicament() == null ? "?" : l.medicament()).toList();
            return noms.size() <= 4 ? String.join(" · ", noms) : String.join(" · ", noms.subList(0, 4)) + " · +" + (noms.size() - 4);
        }
    }

    public enum Enregistrement { ENREGISTRE, NOM_PRIS, ORDONNANCE_VIDE }

    public record Application(boolean trouve, int ajoutees, int ignorees, String nom) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public OrdonnanceTypeRepository(JdbcTemplate jdbc, PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(tm);
    }

    public List<Modele> modeles() {
        Map<UUID, Modele> parId = new LinkedHashMap<>();
        jdbc.query("SELECT id, nom FROM ordonnance_type ORDER BY lower(nom)",
                rs -> { parId.put(rs.getObject(1, UUID.class), new Modele(rs.getObject(1, UUID.class), rs.getString(2), new ArrayList<>())); });
        if (parId.isEmpty()) return List.of();
        jdbc.query("""
                SELECT l.type_id, l.id, l.medicament_id, m.nom, m.dci, m.forme, l.dose, l.duree, l.remarque
                FROM ordonnance_type_ligne l LEFT JOIN medicament m ON m.id = l.medicament_id
                ORDER BY l.type_id, l.ordre""", rs -> {
            Modele m = parId.get(rs.getObject(1, UUID.class));
            if (m != null) m.lignes().add(new LigneType(rs.getObject(2, UUID.class), rs.getLong(3), rs.getString(4),
                    rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9)));
        });
        return new ArrayList<>(parId.values());
    }

    public Optional<Modele> modele(UUID id) {
        return modeles().stream().filter(m -> m.id().equals(id)).findFirst();
    }

    public Enregistrement enregistrer(String nom, UUID consultationId) {
        String n = nom == null ? "" : nom.strip();
        String nomFinal = n.length() > 100 ? n.substring(0, 100) : n;
        try {
            return tx.execute(s -> {
                Integer lignes = jdbc.queryForObject("SELECT count(*) FROM prescription WHERE consultation_id = ?", Integer.class, consultationId);
                if (lignes == null || lignes == 0) return Enregistrement.ORDONNANCE_VIDE;
                UUID id = jdbc.queryForObject("INSERT INTO ordonnance_type (cabinet_id, nom) VALUES (current_cabinet(), ?) RETURNING id",
                        UUID.class, nomFinal);
                jdbc.update("""
                        INSERT INTO ordonnance_type_ligne (cabinet_id, type_id, medicament_id, dose, duree, remarque, ordre)
                        SELECT current_cabinet(), ?, p.medicament_id, p.dose, p.duree, p.remarque, row_number() OVER (ORDER BY p.ordre)
                        FROM prescription p WHERE p.consultation_id = ?""", id, consultationId);
                return Enregistrement.ENREGISTRE;
            });
        } catch (DuplicateKeyException e) {
            return Enregistrement.NOM_PRIS;
        }
    }

    /** Ajoute les lignes cochées du modèle, sauf les médicaments déjà prescrits. */
    public Application appliquer(UUID modeleId, UUID consultationId, List<UUID> lignesChoisies) {
        List<UUID> choisies = lignesChoisies == null ? List.of() : lignesChoisies.stream().distinct().toList();
        return tx.execute(s -> {
            List<String> noms = jdbc.queryForList("SELECT nom FROM ordonnance_type WHERE id = ?", String.class, modeleId);
            if (noms.isEmpty()) return new Application(false, 0, 0, null);
            if (choisies.isEmpty()) return new Application(true, 0, 0, noms.get(0));
            UUID[] ids = choisies.toArray(UUID[]::new);
            Integer total = jdbc.queryForObject("SELECT count(*) FROM ordonnance_type_ligne WHERE type_id = ? AND id = ANY (?)",
                    Integer.class, modeleId, ids);
            int ajoutees = jdbc.update("""
                    INSERT INTO prescription (cabinet_id, consultation_id, medicament_id, dose, duree, remarque)
                    SELECT current_cabinet(), c.id, l.medicament_id, coalesce(l.dose, ''), coalesce(l.duree, ''), coalesce(l.remarque, '')
                    FROM ordonnance_type_ligne l JOIN consultation c ON c.id = ? AND c.supprimee_le IS NULL
                    WHERE l.type_id = ? AND l.id = ANY (?)
                      AND NOT EXISTS (SELECT 1 FROM prescription x WHERE x.consultation_id = c.id AND x.medicament_id = l.medicament_id)
                    ORDER BY l.ordre""", consultationId, modeleId, ids);
            return new Application(true, ajoutees, (total == null ? 0 : total) - ajoutees, noms.get(0));
        });
    }

    public boolean supprimer(UUID modeleId) {
        return jdbc.update("DELETE FROM ordonnance_type WHERE id = ?", modeleId) == 1;
    }
}
