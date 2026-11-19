package com.medsol.web;

import com.medsol.cabinet.CabinetService;
import com.medsol.security.MedsolUser;
import com.medsol.tenant.TenantContext;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Données communes à toutes les pages connectées (en-tête de la mise en page). */
@ControllerAdvice
public class LayoutAdvice {

    private final CabinetService cabinets;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final boolean serveurTest;
    private final String urlProduction;

    public LayoutAdvice(CabinetService cabinets, org.springframework.jdbc.core.JdbcTemplate jdbc,
                        @org.springframework.beans.factory.annotation.Value("${medsol.environnement:}") String environnement,
                        @org.springframework.beans.factory.annotation.Value("${medsol.url-production:}") String urlProduction) {
        this.cabinets = cabinets;
        this.jdbc = jdbc;
        this.serveurTest = "test".equalsIgnoreCase(environnement.strip());
        this.urlProduction = urlProduction.strip();
    }

    /** Serveur de test (MEDSOL_ENVIRONNEMENT=test) : bandeau « données fictives » sur toutes les pages. */
    @ModelAttribute("serveurTest")
    public boolean serveurTest() { return serveurTest; }

    @ModelAttribute("urlProduction")
    public String urlProduction() { return urlProduction; }

    @ModelAttribute("me")
    public MedsolUser me(@AuthenticationPrincipal MedsolUser user) { return user; }

    @ModelAttribute("isDoctor")
    public boolean isDoctor(@AuthenticationPrincipal MedsolUser user) {
        return user != null && "MEDECIN".equals(user.role());
    }

    /** Sous-titre de la marque : la spécialité du cabinet, dans la langue de l'utilisateur. */
    @ModelAttribute("specialiteLibelle")
    public String specialiteLibelle() {
        if (TenantContext.get() == null) return null;
        return cabinets.specialtyLabel(org.springframework.context.i18n.LocaleContextHolder.getLocale().getLanguage());
    }

    /** État de l'abonnement posé par AbonnementFilter (bandeau d'avertissement, lecture seule). */
    @ModelAttribute("abonnement")
    public Object abonnement(jakarta.servlet.http.HttpServletRequest req) {
        return req.getAttribute(com.medsol.abonnement.AbonnementFilter.ATTRIBUT);
    }

    /** Demandes de rendez-vous en ligne en attente (pastille du menu Planning). */
    @ModelAttribute("demandesEnLigne")
    public long demandesEnLigne(@AuthenticationPrincipal MedsolUser user) {
        if (user == null || TenantContext.get() == null) return 0;
        Long n = jdbc.queryForObject("SELECT count(*) FROM reservation WHERE statut = 'EN_ATTENTE' AND debut > now()", Long.class);
        return n == null ? 0 : n;
    }

    @ModelAttribute("isPlateforme")
    public boolean isPlateforme(@AuthenticationPrincipal MedsolUser user) { return user != null && user.plateforme(); }

    /** Cabinet courant (nom, fuseau...) ; null hors session (pages d'erreur, connexion). */
    @ModelAttribute("cab")
    public CabinetService.Cabinet cabinet() {
        return TenantContext.get() == null ? null : cabinets.current();
    }
}
