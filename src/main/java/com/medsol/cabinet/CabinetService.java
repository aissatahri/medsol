package com.medsol.cabinet;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;

/** Paramètres du cabinet courant (la RLS ne laisse voir que sa propre ligne). */
@Service
public class CabinetService {

    public record Cabinet(String nom, ZoneId zone, String langue, String devise, String specialite,
                          String adresse, String telephone, String numeroOrdre, String pays,
                          String mentionsLegales, String identifiantProfessionnel,
                          String ville, String identifiantFiscal, String ice, String taxeProfessionnelle,
                          boolean papierEntete) {
        public LocalDate today() { return LocalDate.now(zone); }

        public boolean maroc() { return "MA".equalsIgnoreCase(pays); }

        /** Identifiant professionnel avec son nom selon le pays : « INPE : … » au Maroc, « RPPS : … » en France. */
        public String identifiantImprime() {
            if (identifiantProfessionnel == null || identifiantProfessionnel.isBlank()) return null;
            String libelle = maroc() ? "INPE" : "FR".equalsIgnoreCase(pays) ? "RPPS" : "Identifiant";
            return libelle + " : " + identifiantProfessionnel.strip();
        }

        /** Ville des documents (« Fait à … ») : la ville saisie, sinon la fin de l'adresse après la dernière virgule. */
        public String villeDocuments() {
            if (ville != null && !ville.isBlank()) return ville.strip();
            if (adresse == null || !adresse.contains(",")) return null;
            String v = adresse.substring(adresse.lastIndexOf(',') + 1).strip();
            return v.isEmpty() ? null : v;
        }

        /** Adresse imprimée, complétée par la ville si elle n'y figure pas déjà. */
        public String adresseComplete() {
            String v = villeDocuments();
            if (adresse == null || adresse.isBlank()) return v;
            if (v == null || adresse.toLowerCase().contains(v.toLowerCase())) return adresse.strip();
            return adresse.strip() + ", " + v;
        }

        /** Identifiants fiscaux marocains sur une ligne : « IF : … · ICE : … · TP : … ». */
        public String ligneFiscale() {
            List<String> l = new java.util.ArrayList<>();
            if (identifiantFiscal != null) l.add("IF : " + identifiantFiscal);
            if (ice != null) l.add("ICE : " + ice);
            if (taxeProfessionnelle != null) l.add("TP : " + taxeProfessionnelle);
            return l.isEmpty() ? null : String.join("  ·  ", l);
        }

        /** Mentions légales imprimées en en-tête des factures, une par ligne. */
        public List<String> mentions() {
            return mentionsLegales == null ? List.of()
                    : Arrays.stream(mentionsLegales.split("\\R")).map(String::strip).filter(s -> !s.isEmpty()).toList();
        }
    }

    private final JdbcTemplate jdbc;

    public CabinetService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Cabinet current() {
        return jdbc.queryForObject("""
                SELECT nom, fuseau, langue, devise, specialite, adresse, telephone, numero_ordre, pays,
                       mentions_legales, identifiant_professionnel, ville, identifiant_fiscal, ice,
                       taxe_professionnelle, papier_entete FROM cabinet""",
                (rs, i) -> new Cabinet(rs.getString("nom"), ZoneId.of(rs.getString("fuseau")),
                        rs.getString("langue"), rs.getString("devise"), rs.getString("specialite"),
                        rs.getString("adresse"), rs.getString("telephone"), rs.getString("numero_ordre"), rs.getString("pays"),
                        rs.getString("mentions_legales"), rs.getString("identifiant_professionnel"),
                        rs.getString("ville"), rs.getString("identifiant_fiscal"), rs.getString("ice"),
                        rs.getString("taxe_professionnelle"), rs.getBoolean("papier_entete")));
    }

    public void updateDocuments(String ville, String identifiantFiscal, String ice, String taxeProfessionnelle, boolean papierEntete) {
        jdbc.update("""
                UPDATE cabinet SET ville = ?, identifiant_fiscal = ?, ice = ?, taxe_professionnelle = ?, papier_entete = ?
                WHERE id = current_cabinet()""",
                blank(ville), blank(identifiantFiscal), blank(ice), blank(taxeProfessionnelle), papierEntete);
    }

    public String specialtyLabel(String lang) {
        return jdbc.query("SELECT coalesce(s.libelle->>?, s.libelle->>'fr') FROM cabinet c JOIN specialite s ON s.code = c.specialite",
                (rs, i) -> rs.getString(1), lang).stream().findFirst().orElse(null);
    }

    public void updateContact(String nom, String adresse, String telephone, String numeroOrdre) {
        jdbc.update("UPDATE cabinet SET nom = ?, adresse = ?, telephone = ?, numero_ordre = ? WHERE id = current_cabinet()",
                nom.trim(), blank(adresse), blank(telephone), blank(numeroOrdre));
    }

    public void updateFacturation(String mentions, String identifiant) {
        jdbc.update("UPDATE cabinet SET mentions_legales = ?, identifiant_professionnel = ? WHERE id = current_cabinet()",
                blank(mentions), blank(identifiant));
    }

    /** Valeur suivante d'un compteur du cabinet courant (atomique, sans trou en cas de concurrence). */
    public long nextValue(String name) {
        return jdbc.queryForObject("""
                INSERT INTO cabinet_compteur (cabinet_id, nom, valeur) VALUES (current_cabinet(), ?, 1)
                ON CONFLICT (cabinet_id, nom) DO UPDATE SET valeur = cabinet_compteur.valeur + 1
                RETURNING valeur""", Long.class, name);
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
