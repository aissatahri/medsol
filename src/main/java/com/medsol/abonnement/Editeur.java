package com.medsol.abonnement;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * L'éditeur de MedSol (vous) : raison sociale et identifiants imprimés sur les factures d'abonnement, instructions de
 * paiement (RIB) affichées aux cabinets. Saisi par l'administrateur dans l'espace Plateforme.
 */
@Service
public class Editeur {

    /** Champs, dans l'ordre du formulaire de l'espace Plateforme. */
    public static final Map<String, String> CHAMPS = champs();

    private static Map<String, String> champs() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("raison_sociale", "Nom ou raison sociale");
        m.put("statut_juridique", "Statut (ex. Auto-entrepreneur)");
        m.put("adresse", "Adresse");
        m.put("ville", "Ville");
        m.put("telephone", "Téléphone");
        m.put("email", "E-mail de facturation");
        m.put("ice", "ICE");
        m.put("identifiant_fiscal", "Identifiant fiscal (IF)");
        m.put("taxe_professionnelle", "Taxe professionnelle (TP)");
        m.put("rc", "Registre du commerce (RC) ou n° d'auto-entrepreneur");
        m.put("mention_tva", "Mention TVA (ex. TVA non applicable)");
        m.put("instructions_paiement", "Instructions de paiement (RIB, bénéficiaire, référence à indiquer)");
        return java.util.Collections.unmodifiableMap(m);
    }

    private final JdbcTemplate plateforme;
    private final String instructionsParDefaut;

    public Editeur(@Qualifier("plateforme") JdbcTemplate plateforme,
                   @Value("${medsol.billing.manual-instructions:}") String instructionsParDefaut) {
        this.plateforme = plateforme;
        this.instructionsParDefaut = instructionsParDefaut;
    }

    public Map<String, String> tous() {
        Map<String, String> m = new LinkedHashMap<>();
        CHAMPS.keySet().forEach(k -> m.put(k, ""));
        plateforme.query("SELECT cle, valeur FROM plateforme_reglage", rs -> {
            if (m.containsKey(rs.getString(1))) m.put(rs.getString(1), rs.getString(2));
        });
        return m;
    }

    public String get(String cle) {
        return plateforme.query("SELECT valeur FROM plateforme_reglage WHERE cle = ?", (rs, i) -> rs.getString(1), cle)
                .stream().findFirst().orElse("");
    }

    /** Instructions affichées sur la page Abonnement (réglage de la plateforme, sinon MEDSOL_BILLING_INSTRUCTIONS). */
    public String instructionsPaiement() {
        String v = get("instructions_paiement");
        return v.isBlank() ? instructionsParDefaut : v;
    }

    public void enregistrer(Map<String, String> valeurs) {
        valeurs.forEach((cle, valeur) -> {
            if (!CHAMPS.containsKey(cle)) return;
            String v = valeur == null ? "" : valeur.strip();
            if (v.length() > ("instructions_paiement".equals(cle) ? 1500 : 200)) v = v.substring(0, "instructions_paiement".equals(cle) ? 1500 : 200);
            if (v.isEmpty()) {
                plateforme.update("DELETE FROM plateforme_reglage WHERE cle = ?", cle);
            } else {
                plateforme.update("""
                        INSERT INTO plateforme_reglage (cle, valeur) VALUES (?, ?)
                        ON CONFLICT (cle) DO UPDATE SET valeur = excluded.valeur, modifie_le = now()""", cle, v);
            }
        });
    }

    /** En-tête de la facture : nom, statut, adresse, contacts, puis les identifiants sur une ligne. */
    public List<String> entete() {
        Map<String, String> e = tous();
        List<String> l = new ArrayList<>();
        if (!e.get("statut_juridique").isBlank()) l.add(e.get("statut_juridique"));
        String adresse = String.join(", ", java.util.stream.Stream.of(e.get("adresse"), e.get("ville")).filter(s -> !s.isBlank()).toList());
        if (!adresse.isBlank()) l.add(adresse);
        String contact = String.join("  ·  ", java.util.stream.Stream.of(
                e.get("telephone").isBlank() ? "" : "Tél. : " + e.get("telephone"), e.get("email")).filter(s -> !s.isBlank()).toList());
        if (!contact.isBlank()) l.add(contact);
        List<String> ids = new ArrayList<>();
        for (String[] k : new String[][]{{"ice", "ICE"}, {"identifiant_fiscal", "IF"}, {"taxe_professionnelle", "TP"}, {"rc", "RC"}}) {
            if (!e.get(k[0]).isBlank()) ids.add(k[1] + " : " + e.get(k[0]));
        }
        if (!ids.isEmpty()) l.add(String.join("  ·  ", ids));
        return l;
    }

    /** Réglage de la plateforme hors formulaire de l'éditeur (ex. offre fondateur) ; null s'il est absent. */
    static String reglage(JdbcTemplate plateforme, String cle) {
        return plateforme.query("SELECT valeur FROM plateforme_reglage WHERE cle = ?", (rs, i) -> rs.getString(1), cle)
                .stream().findFirst().orElse(null);
    }

    static void ecrire(JdbcTemplate plateforme, String cle, String valeur) {
        if (valeur == null || valeur.isBlank()) {
            plateforme.update("DELETE FROM plateforme_reglage WHERE cle = ?", cle);
        } else {
            plateforme.update("""
                    INSERT INTO plateforme_reglage (cle, valeur) VALUES (?, ?)
                    ON CONFLICT (cle) DO UPDATE SET valeur = excluded.valeur, modifie_le = now()""", cle, valeur);
        }
    }

    public String raisonSociale() {
        String r = get("raison_sociale");
        return r.isBlank() ? "MedSol" : r;
    }
}
