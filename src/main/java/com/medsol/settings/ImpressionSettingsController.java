package com.medsol.settings;

import com.medsol.audit.AuditService;
import com.medsol.cabinet.CabinetService;
import com.medsol.documents.DocumentController;
import com.medsol.documents.DocumentsPdf;
import com.medsol.documents.ImpressionReglages;
import com.medsol.documents.MiseEnPage;
import com.medsol.dossier.ExamenRepository;
import com.medsol.dossier.OrdonnanceRepository;
import com.medsol.security.MedsolUser;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Paramètres › Impression (médecin) : pour chaque document, police, marges et, élément par élément, affichage, taille,
 * gras et position fixe en mm. Aperçu sur données fictives, avec une grille de calage pour la feuille préimprimée.
 */
@Controller
@RequestMapping("/settings/impression")
public class ImpressionSettingsController {

    private final ImpressionReglages reglages;
    private final DocumentsPdf pdf;
    private final CabinetService cabinets;
    private final AuditService audit;

    public ImpressionSettingsController(ImpressionReglages reglages, DocumentsPdf pdf, CabinetService cabinets, AuditService audit) {
        this.reglages = reglages;
        this.pdf = pdf;
        this.cabinets = cabinets;
        this.audit = audit;
    }

    @GetMapping
    public String page(@RequestParam(defaultValue = "ORDONNANCE") String document, Model model) {
        MiseEnPage.Type type = MiseEnPage.TYPES.stream().filter(t -> t.cle().equals(document)).findFirst().orElse(MiseEnPage.TYPES.get(0));
        MiseEnPage m = reglages.pour(type.cle());
        model.addAttribute("types", MiseEnPage.TYPES);
        model.addAttribute("type", type);
        model.addAttribute("m", m);
        model.addAttribute("elements", m.tousLesElements());
        model.addAttribute("polices", MiseEnPage.POLICES);
        model.addAttribute("preimprime", cabinets.current().papierEntete());
        return "settings/impression";
    }

    @PostMapping
    public String enregistrer(@RequestParam Map<String, String> p, @RequestParam(defaultValue = "enregistrer") String action, RedirectAttributes ra) {
        String document = p.getOrDefault("document", "");
        MiseEnPage.type(document);
        if ("reinitialiser".equals(action)) {
            reglages.reinitialiser(document);
            audit.log("IMPRESSION_REINITIALISEE", "cabinet", document);
            ra.addFlashAttribute("done", "print.reset.done");
        } else {
            reglages.enregistrer(lire(document, p, false));
            audit.log("IMPRESSION_REGLEE", "cabinet", document);
            ra.addFlashAttribute("done", "settings.saved");
        }
        return "redirect:/settings/impression?document=" + document;
    }

    /** Aperçu du document avec les valeurs du formulaire (non enregistrées), sur des données fictives. */
    @PostMapping("/apercu")
    public ResponseEntity<byte[]> apercu(@RequestParam Map<String, String> p, @AuthenticationPrincipal MedsolUser user) {
        String document = p.getOrDefault("document", "");
        MiseEnPage m = lire(document, p, "true".equals(p.get("grille")));
        DocumentsPdf.Cadre cadre = DocumentController.cadre(cabinets, DocumentsPdf.docteur(user.fullName()), m);
        LocalDate jour = cabinets.current().today();
        String medecin = DocumentsPdf.docteur(user.fullName());
        byte[] contenu = switch (document) {
            case "ORDONNANCE" -> pdf.ordonnance(cadre, "Alami", "Youssef", "54 ans", jour, List.of(
                    new OrdonnanceRepository.Ligne(UUID.randomUUID(), 0, "AMLOR 5 MG", "1 comprimé le matin", "3 mois", "au petit-déjeuner",
                            "COMPRIME", "AMLODIPINE"),
                    new OrdonnanceRepository.Ligne(UUID.randomUUID(), 0, "KARDEGIC 75 MG", "1 sachet à midi", "3 mois", null,
                            "SACHET", "ACIDE ACETYLSALICYLIQUE")),
                    new DocumentsPdf.ProchainRdv(jour.plusMonths(3), LocalTime.of(10, 30)));
            case "ANALYSE" -> pdf.demande(cadre, ExamenRepository.Genre.ANALYSE, "ALAMI", "Youssef", jour, List.of(
                    examen("Numération formule sanguine", "NFS", "à jeun"), examen("Glycémie à jeun", "GAJ", null),
                    examen("Bilan lipidique", null, null)));
            case "IMAGERIE" -> pdf.demande(cadre, ExamenRepository.Genre.IMAGERIE, "ALAMI", "Youssef", jour, List.of(
                    examen("Radiographie thoracique de face", null, null), examen("Échographie abdominale", null, "vessie pleine")));
            case "CERTIFICAT" -> pdf.certificat(cadre, "Certificat Médical de Consultation",
                    "Je soussigné " + medecin + ", certifie avoir examiné Monsieur Youssef ALAMI le " + jour.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")) + ".",
                    medecin, jour);
            case "LETTRE" -> pdf.lettre(cadre, cadre.ville() == null ? "…" : cadre.ville(), jour, "Dr Confrère, cardiologue", "Youssef ALAMI",
                    LocalDate.of(1972, 3, 14), "Cher confrère,\n\nJe vous adresse Monsieur Youssef ALAMI, 54 ans, pour avis spécialisé.\n\n"
                            + "Je vous remercie de bien vouloir le recevoir.\n\nConfraternellement.", medecin);
            case "COMPTE_RENDU" -> pdf.compteRendu(cadre, "Compte rendu d'échocardiographie", "Échocardiographie transthoracique", jour,
                    "Youssef ALAMI", LocalDate.of(1972, 3, 14), 54, "Ventricule gauche non dilaté, fonction systolique conservée.",
                    "Examen dans les limites de la normale.", medecin);
            default -> throw new IllegalArgumentException(document);
        };
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename("apercu-" + document.toLowerCase() + ".pdf").build().toString())
                .cacheControl(CacheControl.noStore()).body(contenu);
    }

    private static ExamenRepository.Examen examen(String nom, String code, String description) {
        return new ExamenRepository.Examen(UUID.randomUUID(), null, nom, code, description, null, null);
    }

    /** Réglages lus du formulaire ; champ vide = valeur par défaut de MedSol. */
    static MiseEnPage lire(String document, Map<String, String> p, boolean grille) {
        MiseEnPage.Type type = MiseEnPage.type(document);
        String police = p.get("police") != null && MiseEnPage.POLICES.contains(p.get("police")) ? p.get("police") : null;
        Map<String, MiseEnPage.Element> elements = new LinkedHashMap<>();
        for (MiseEnPage.Modele e : type.elements()) {
            String c = e.cle();
            // case à cocher : on ne garde que l'écart avec l'affichage par défaut de l'élément
            Boolean visible = null;
            if (e.masquable() && p.containsKey("present_" + c)) {
                boolean coche = "true".equals(p.get("v_" + c));
                if (coche != e.visible()) visible = coche;
            }
            Boolean gras = "oui".equals(p.get("g_" + c)) ? Boolean.TRUE : "non".equals(p.get("g_" + c)) ? Boolean.FALSE : null;
            Float x = e.placable() ? nombre(p.get("x_" + c), 0, 300) : null;
            Float y = e.placable() ? nombre(p.get("y_" + c), 0, 300) : null;
            if (x == null || y == null) { x = null; y = null; }
            MiseEnPage.Element el = new MiseEnPage.Element(visible, x, y, nombre(p.get("t_" + c), 5, 48), gras);
            if (!el.equals(MiseEnPage.Element.AUTO)) elements.put(c, el);
        }
        return new MiseEnPage(document, police, nombre(p.get("haut"), 0, 150), nombre(p.get("bas"), 0, 150),
                nombre(p.get("gauche"), 0, 100), nombre(p.get("droite"), 0, 100), elements, grille);
    }

    private static Float nombre(String s, float min, float max) {
        if (s == null || s.isBlank()) return null;
        try {
            float v = Float.parseFloat(s.strip().replace(',', '.'));
            return v < min || v > max || Float.isNaN(v) ? null : Math.round(v * 10) / 10f;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
