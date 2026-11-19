package com.medsol.abonnement;

import com.medsol.audit.AuditService;
import com.medsol.cabinet.CabinetService;
import com.medsol.security.MedsolUser;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.time.Instant;
import java.util.Set;

/** Page d'abonnement du cabinet (état, tarifs, paiement en ligne ou instructions) et rappel de Stripe. */
@Controller
public class AbonnementController {

    private static final Logger LOG = LoggerFactory.getLogger(AbonnementController.class);
    private static final Set<String> PERIODES = Set.of("MENSUEL", "ANNUEL");

    private final AbonnementService abonnements;
    private final StripeService stripe;
    private final CabinetService cabinets;
    private final AuditService audit;
    private final Editeur editeur;
    private final com.medsol.documents.FacturesPdf factures;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    private final com.medsol.web.Textes textes;

    private final Croissance croissance;
    private final String baseUrl;

    public AbonnementController(AbonnementService abonnements, StripeService stripe, CabinetService cabinets, AuditService audit,
                                Editeur editeur, com.medsol.documents.FacturesPdf factures,
                                org.springframework.jdbc.core.JdbcTemplate jdbc, com.medsol.web.Textes textes, Croissance croissance,
                                @Value("${medsol.base-url:}") String baseUrl) {
        this.croissance = croissance;
        this.baseUrl = baseUrl;
        this.textes = textes;
        this.abonnements = abonnements;
        this.stripe = stripe;
        this.cabinets = cabinets;
        this.audit = audit;
        this.editeur = editeur;
        this.factures = factures;
        this.jdbc = jdbc;
    }

    /** Facture d'abonnement (PDF) d'un paiement du cabinet : pour la comptabilité du médecin. */
    @GetMapping("/abonnement/paiements/{id}/facture")
    public ResponseEntity<byte[]> facture(@PathVariable java.util.UUID id, @AuthenticationPrincipal MedsolUser user) {
        if (!"MEDECIN".equals(user.role())) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        AbonnementService.Paiement p = abonnements.paiement(id).orElse(null);
        if (p == null || p.numero() == null) return ResponseEntity.notFound().build();
        CabinetService.Cabinet cab = cabinets.current();
        java.util.List<String> client = new java.util.ArrayList<>();
        client.add(cab.nom());
        jdbc.queryForList("SELECT nom_complet FROM utilisateur WHERE role = 'MEDECIN' ORDER BY cree_le LIMIT 1", String.class)
                .stream().findFirst().map(com.medsol.documents.DocumentsPdf::docteur).ifPresent(client::add);
        if (cab.adresseComplete() != null) client.add(cab.adresseComplete());
        if (cab.ice() != null) client.add("ICE : " + cab.ice());
        java.time.format.DateTimeFormatter j = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");
        String designation = "Abonnement MedSol Cabinet — " + ("ANNUEL".equals(p.periode()) ? "annuel" : "mensuel")
                + "\ndu " + p.du().format(j) + " au " + p.au().format(j);
        java.time.LocalDate date = p.le().atZone(cab.zone()).toLocalDate();
        String reglement = "Payée le " + date.format(j) + " par " + ("STRIPE".equals(p.mode()) ? "carte bancaire" : "virement ou versement")
                + (p.reference() == null ? "" : " (réf. " + p.reference() + ")") + ".";
        byte[] pdf = factures.factureAbonnement(new com.medsol.documents.FacturesPdf.FactureAbonnement(
                editeur.raisonSociale(), editeur.entete(), editeur.get("mention_tva"), client, p.numero(), date,
                designation, p.montant(), p.devise(), reglement));
        audit.log("FACTURE_ABONNEMENT", "paiement_abonnement", id);
        return ResponseEntity.ok().contentType(org.springframework.http.MediaType.APPLICATION_PDF)
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        org.springframework.http.ContentDisposition.inline().filename("Facture_" + p.numero() + ".pdf").build().toString())
                .cacheControl(org.springframework.http.CacheControl.noStore()).body(pdf);
    }

    @GetMapping("/abonnement")
    public String page(Model model, @AuthenticationPrincipal MedsolUser user, HttpServletRequest req) {
        CabinetService.Cabinet cab = cabinets.current();
        AbonnementService.Etat etat = abonnements.etat(cab.today()).orElseThrow();
        model.addAttribute("etat", etat);
        model.addAttribute("prix", abonnements.prix(etat.formule(), cab.devise()));
        model.addAttribute("paiements", abonnements.paiements());
        model.addAttribute("stripe", stripe.actif());
        model.addAttribute("instructions", editeur.instructionsPaiement());
        // parrainage : lien personnel du cabinet, message WhatsApp prêt à envoyer, confrères inscrits
        String base = baseUrl.isBlank() ? ServletUriComponentsBuilder.fromContextPath(req).build().toUriString() : baseUrl.replaceAll("/+$", "");
        String lien = base + "/register?parrain=" + croissance.code(user.cabinetId());
        model.addAttribute("lienParrainage", lien);
        model.addAttribute("whatsappParrainage", "https://wa.me/?text=" + java.net.URLEncoder.encode(
                textes.t("ref.whatsapp", lien), java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20"));
        model.addAttribute("filleuls", croissance.filleuls(user.cabinetId()));
        return "abonnement/page";
    }

    /** Médecin : paiement en ligne de la période choisie (Stripe Checkout). */
    @PostMapping("/abonnement/payer")
    public String payer(@RequestParam String periode, @AuthenticationPrincipal MedsolUser user, HttpServletRequest req,
                        RedirectAttributes ra) {
        if (!"MEDECIN".equals(user.role()) || !PERIODES.contains(periode) || !stripe.actif()) {
            ra.addFlashAttribute("erreur", textes.t("msg.sub.unavailable"));
            return "redirect:/abonnement";
        }
        CabinetService.Cabinet cab = cabinets.current();
        AbonnementService.Etat etat = abonnements.etat(cab.today()).orElseThrow();
        AbonnementService.Prix prix = abonnements.prix(etat.formule(), cab.devise());
        String base = ServletUriComponentsBuilder.fromContextPath(req).build().toUriString();
        try {
            String url = stripe.session(user.cabinetId(), periode, prix.montant(periode), prix.devise(),
                    etat.formuleNom() + " — " + ("ANNUEL".equals(periode) ? "1 an" : "1 mois") + " — " + cab.nom(),
                    base + "/abonnement?merci", base + "/abonnement");
            audit.log("ABONNEMENT_PAIEMENT_EN_LIGNE", "cabinet", periode);
            return "redirect:" + url;
        } catch (Exception e) {
            LOG.warn("Session Stripe impossible : {}", e.getMessage());
            ra.addFlashAttribute("erreur", textes.t("msg.sub.failed"));
            return "redirect:/abonnement";
        }
    }

    /** Rappel signé de Stripe (public, sans session) : prolonge l'abonnement payé, une seule fois par paiement. */
    @PostMapping("/stripe/webhook")
    @ResponseBody
    public ResponseEntity<String> webhook(@RequestBody String charge, @RequestHeader(value = "Stripe-Signature", required = false) String signature) {
        try {
            stripe.rappel(charge, signature, Instant.now()).ifPresent(p -> {
                var fin = abonnements.prolonger(p.cabinetId(), p.periode(), p.montant(), p.devise(), "STRIPE", p.reference(), "Stripe",
                        java.time.LocalDate.now());
                LOG.info("Abonnement {} : paiement Stripe {} {}", p.cabinetId(), p.reference(), fin.map(f -> "→ " + f).orElse("déjà enregistré"));
            });
            return ResponseEntity.ok("ok");
        } catch (SecurityException e) {
            LOG.warn("Rappel Stripe refusé : {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("signature");
        }
    }
}
