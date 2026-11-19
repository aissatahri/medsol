package com.medsol.abonnement;

import com.medsol.security.MedsolUser;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Espace administrateur de MedSol (ROLE_PLATEFORME) : cabinets et abonnements, paiements manuels (virement, mobile
 * money, espèces), essais, suspensions, tarifs des formules. Lit tous les cabinets : connexions du propriétaire.
 * Aucune donnée médicale n'y est lue (seulement des comptes).
 */
@Controller
@RequestMapping("/plateforme")
public class PlateformeController {

    /** Canal d'acquisition : cabinets inscrits par ce canal, et combien sont devenus clients (abonnement payé). */
    public record Canal(String source, long inscrits, long clients) {}

    public record CabinetLigne(UUID id, String nom, String pays, String specialite, String devise, String statut, LocalDate fin,
                               long jours, String email, long patients, java.time.Instant creeLe, String source, String sourceDetail,
                               String parrain, boolean fondateur) {
        public boolean echu() { return jours < 0; }
    }

    public record PrixLigne(String formule, String devise, BigDecimal mensuel, BigDecimal annuel) {
        /** Mois offerts par l'abonnement annuel (12 − annuel / mensuel), ex. « 2 » ou « 1,5 ». */
        public String moisOfferts() {
            if (mensuel.signum() <= 0) return null;
            BigDecimal m = BigDecimal.valueOf(12).subtract(annuel.divide(mensuel, 1, java.math.RoundingMode.HALF_UP));
            return m.signum() <= 0 ? null : m.stripTrailingZeros().toPlainString().replace('.', ',');
        }
    }

    private static final Set<String> PERIODES = Set.of("MENSUEL", "ANNUEL");

    private final JdbcTemplate plateforme;
    private final AbonnementService abonnements;
    private final com.medsol.security.DoubleAuthentification deuxFacteurs;
    private final com.medsol.security.Jetons jetons;
    private final Editeur editeur;
    private final com.medsol.documents.FicheCommerciale fiche;
    private final String baseUrl;
    @org.springframework.beans.factory.annotation.Autowired private Croissance croissance;

    public PlateformeController(@Qualifier("plateforme") JdbcTemplate plateforme, AbonnementService abonnements,
                                com.medsol.security.DoubleAuthentification deuxFacteurs, com.medsol.security.Jetons jetons,
                                Editeur editeur, com.medsol.documents.FicheCommerciale fiche,
                                @org.springframework.beans.factory.annotation.Value("${medsol.base-url:}") String baseUrl) {
        this.editeur = editeur;
        this.fiche = fiche;
        this.baseUrl = baseUrl;
        this.plateforme = plateforme;
        this.abonnements = abonnements;
        this.deuxFacteurs = deuxFacteurs;
        this.jetons = jetons;
    }

    /**
     * Médecin qui a perdu son téléphone et ses codes de secours (identité vérifiée par le support, hors application) :
     * la double authentification est retirée et ses sessions fermées. Inscrit au journal d'accès du cabinet.
     */
    @PostMapping("/cabinets/{id}/2fa")
    public String retirer2fa(@PathVariable UUID id, @AuthenticationPrincipal MedsolUser user,
                             jakarta.servlet.http.HttpServletRequest req, RedirectAttributes ra) {
        List<UUID> medecins = plateforme.queryForList("SELECT id FROM utilisateur WHERE cabinet_id = ? AND role = 'MEDECIN' AND totp_actif",
                UUID.class, id);
        medecins.forEach(m -> {
            deuxFacteurs.retirer(m, true);
            jetons.journal(m, "DOUBLE_AUTH_RETIREE_PLATEFORME", req.getRemoteAddr());
        });
        ra.addFlashAttribute(medecins.isEmpty() ? "erreur" : "succes", medecins.isEmpty()
                ? "Le médecin de ce cabinet n'a pas de double authentification active."
                : "Double authentification du médecin retirée (par " + user.email() + ") : il pourra la réactiver depuis « Mon compte ».");
        return "redirect:/plateforme";
    }

    @GetMapping
    public String page(@RequestParam(defaultValue = "") String q, Model model) {
        String like = "%" + q.strip().toLowerCase().replace("%", "") + "%";
        List<CabinetLigne> cabinets = plateforme.query("""
                SELECT c.id, c.nom, c.pays, c.specialite, c.devise, a.statut, a.fin, a.fin - current_date AS jours,
                       (SELECT u.email FROM utilisateur u WHERE u.cabinet_id = c.id AND u.role = 'MEDECIN' ORDER BY u.cree_le LIMIT 1) AS email,
                       (SELECT count(*) FROM patient p WHERE p.cabinet_id = c.id) AS patients, c.cree_le, c.source, c.source_detail,
                       (SELECT pa.nom FROM cabinet pa WHERE pa.id = c.parrain_id), c.tarif_fondateur
                FROM cabinet c JOIN abonnement a ON a.cabinet_id = c.id
                WHERE NOT c.interne AND (? = '%%' OR lower(c.nom) LIKE ? OR EXISTS (SELECT 1 FROM utilisateur u WHERE u.cabinet_id = c.id AND lower(u.email) LIKE ?))
                ORDER BY a.fin, c.nom LIMIT 300""",
                (rs, i) -> new CabinetLigne(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getObject(7, LocalDate.class), rs.getLong(8), rs.getString(9), rs.getLong(10),
                        rs.getTimestamp(11).toInstant(), rs.getString(12), rs.getString(13), rs.getString(14), rs.getBoolean(15)),
                like, like, like);
        model.addAttribute("fondateur", croissance.fondateur());
        model.addAttribute("canaux", plateforme.query("""
                SELECT coalesce(c.source, 'NON_RENSEIGNE'), count(*),
                       count(*) FILTER (WHERE EXISTS (SELECT 1 FROM paiement_abonnement p WHERE p.cabinet_id = c.id))
                FROM cabinet c WHERE NOT c.interne GROUP BY 1 ORDER BY 2 DESC, 1""",
                (rs, i) -> new Canal(rs.getString(1), rs.getLong(2), rs.getLong(3))));
        model.addAttribute("q", q.strip());
        model.addAttribute("cabinets", cabinets);
        model.addAttribute("actifs", cabinets.stream().filter(c -> "ACTIF".equals(c.statut()) && !c.echu()).count());
        model.addAttribute("essais", cabinets.stream().filter(c -> "ESSAI".equals(c.statut()) && !c.echu()).count());
        model.addAttribute("echus", cabinets.stream().filter(CabinetLigne::echu).count());
        model.addAttribute("champsEditeur", Editeur.CHAMPS);
        model.addAttribute("editeur", editeur.tous());
        model.addAttribute("tarifs", plateforme.query("SELECT formule_code, devise, mensuel, annuel FROM formule_prix ORDER BY formule_code, devise",
                (rs, i) -> new PrixLigne(rs.getString(1), rs.getString(2), rs.getBigDecimal(3), rs.getBigDecimal(4))));
        return "plateforme/page";
    }

    /** Paiement reçu hors ligne (virement, mobile money, espèces) : l'abonnement est prolongé. */
    @PostMapping("/cabinets/{id}/paiement")
    public String paiement(@PathVariable UUID id, @RequestParam String periode, @RequestParam String montant,
                           @RequestParam String devise, @RequestParam(defaultValue = "") String reference,
                           @AuthenticationPrincipal MedsolUser user, RedirectAttributes ra) {
        BigDecimal m;
        try {
            m = new BigDecimal(montant.strip().replace(',', '.'));
        } catch (NumberFormatException e) {
            m = null;
        }
        if (m == null || m.signum() < 0 || !PERIODES.contains(periode) || !devise.matches("[A-Z]{3}")) {
            ra.addFlashAttribute("erreur", "Paiement invalide.");
        } else {
            abonnements.prolonger(id, periode, m, devise, "MANUEL", reference, user.email(), LocalDate.now())
                    .ifPresentOrElse(fin -> ra.addFlashAttribute("succes", "Paiement enregistré : abonnement actif jusqu'au " + fin + "."),
                            () -> ra.addFlashAttribute("erreur", "Cette référence de paiement est déjà enregistrée."));
        }
        return "redirect:/plateforme";
    }

    @PostMapping("/cabinets/{id}/essai")
    public String essai(@PathVariable UUID id, @RequestParam(defaultValue = "15") int jours, RedirectAttributes ra) {
        abonnements.prolongerEssai(id, Math.max(1, Math.min(jours, 90)));
        ra.addFlashAttribute("succes", "Essai prolongé.");
        return "redirect:/plateforme";
    }

    @PostMapping("/cabinets/{id}/suspension")
    public String suspension(@PathVariable UUID id, @RequestParam boolean suspendu, RedirectAttributes ra) {
        abonnements.suspendre(id, suspendu);
        ra.addFlashAttribute("succes", suspendu ? "Cabinet suspendu (lecture seule)." : "Cabinet réactivé.");
        return "redirect:/plateforme";
    }

    /**
     * Fiche de présentation (PDF A4) à laisser au cabinet après une visite : tarif MAD, coordonnées de l'éditeur, QR code
     * vers l'inscription avec le canal « Visite ou démonstration » présélectionné.
     */
    @GetMapping("/fiche-presentation")
    public org.springframework.http.ResponseEntity<byte[]> fiche(jakarta.servlet.http.HttpServletRequest req) {
        String base = baseUrl.isBlank()
                ? org.springframework.web.servlet.support.ServletUriComponentsBuilder.fromContextPath(req).build().toUriString()
                : baseUrl.replaceAll("/+$", "");
        PrixLigne prix = plateforme.query("SELECT formule_code, devise, mensuel, annuel FROM formule_prix WHERE formule_code = 'CABINET' AND devise = 'MAD'",
                (rs, i) -> new PrixLigne(rs.getString(1), rs.getString(2), rs.getBigDecimal(3), rs.getBigDecimal(4))).stream().findFirst().orElse(null);
        java.util.function.Function<BigDecimal, String> dh = v -> String.format(java.util.Locale.FRANCE, "%,d DH", v.setScale(0, java.math.RoundingMode.HALF_UP).longValue())
                .replace(' ', ' ').replace(' ', ' ');
        var e = editeur.tous();
        List<String> contact = new java.util.ArrayList<>();
        if (!e.get("raison_sociale").isBlank()) contact.add(e.get("raison_sociale"));
        if (!e.get("telephone").isBlank()) contact.add("Téléphone et WhatsApp : " + e.get("telephone"));
        if (!e.get("email").isBlank()) contact.add("E-mail : " + e.get("email"));
        Croissance.Fondateur fo = croissance.fondateur();
        String offre = fo.ouverte() ? "Offre fondateur : " + dh.apply(fo.mensuel()) + " / mois ou " + dh.apply(fo.annuel())
                + " / an, garanti à vie pour les " + fo.places() + " premiers cabinets (" + fo.restantes() + " places restantes)" : null;
        byte[] pdf = fiche.pdf(new com.medsol.documents.FicheCommerciale.Fiche(base + "/register?source=VISITE",
                base.replaceFirst("^https?://", ""), prix == null ? null : dh.apply(prix.mensuel()),
                prix == null ? null : dh.apply(prix.annuel()), prix == null ? null : prix.moisOfferts(), contact, offre));
        return org.springframework.http.ResponseEntity.ok().contentType(org.springframework.http.MediaType.APPLICATION_PDF)
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        org.springframework.http.ContentDisposition.inline().filename("MedSol-fiche-presentation.pdf").build().toString())
                .body(pdf);
    }

    /** Offre fondateur : prix MAD garantis à vie pour un nombre limité de nouveaux cabinets. */
    @PostMapping("/fondateur")
    public String fondateur(@RequestParam(defaultValue = "false") boolean actif, @RequestParam(defaultValue = "") String mensuel,
                            @RequestParam(defaultValue = "") String annuel, @RequestParam(defaultValue = "0") int places, RedirectAttributes ra) {
        try {
            BigDecimal m = mensuel.isBlank() ? null : new BigDecimal(mensuel.strip().replace(',', '.'));
            BigDecimal a = annuel.isBlank() ? null : new BigDecimal(annuel.strip().replace(',', '.'));
            if ((m != null && m.signum() < 0) || (a != null && a.signum() < 0) || places < 0 || places > 10000
                    || (actif && (m == null || a == null || places == 0))) throw new NumberFormatException();
            croissance.reglerFondateur(actif, m, a, places);
            ra.addFlashAttribute("succes", actif ? "Offre fondateur active." : "Offre fondateur enregistrée (inactive).");
        } catch (NumberFormatException e) {
            ra.addFlashAttribute("erreur", "Offre fondateur invalide : prix mensuel, prix annuel et nombre de places sont nécessaires pour l'activer.");
        }
        return "redirect:/plateforme";
    }

    /** Mentions de l'éditeur (factures d'abonnement) et instructions de paiement. */
    @PostMapping("/editeur")
    public String editeur(@RequestParam java.util.Map<String, String> valeurs, RedirectAttributes ra) {
        editeur.enregistrer(valeurs);
        ra.addFlashAttribute("succes", "Informations de l'éditeur enregistrées.");
        return "redirect:/plateforme";
    }

    @PostMapping("/tarifs")
    public String tarif(@RequestParam String formule, @RequestParam String devise, @RequestParam String mensuel,
                        @RequestParam String annuel, RedirectAttributes ra) {
        try {
            BigDecimal m = new BigDecimal(mensuel.strip().replace(',', '.'));
            BigDecimal a = new BigDecimal(annuel.strip().replace(',', '.'));
            devise = devise.strip().toUpperCase(java.util.Locale.ROOT);
            if (m.signum() < 0 || a.signum() < 0 || !devise.matches("[A-Z]{3}")) throw new NumberFormatException();
            plateforme.update("""
                    INSERT INTO formule_prix (formule_code, devise, mensuel, annuel) VALUES (?, ?, ?, ?)
                    ON CONFLICT (formule_code, devise) DO UPDATE SET mensuel = excluded.mensuel, annuel = excluded.annuel""",
                    formule, devise, m, a);
            ra.addFlashAttribute("succes", "Tarif " + devise + " enregistré : " + m.stripTrailingZeros().toPlainString() + " par mois, "
                    + a.stripTrailingZeros().toPlainString() + " par an.");
        } catch (NumberFormatException | org.springframework.dao.DataIntegrityViolationException e) {
            ra.addFlashAttribute("erreur", "Tarif invalide.");
        }
        return "redirect:/plateforme";
    }
}
