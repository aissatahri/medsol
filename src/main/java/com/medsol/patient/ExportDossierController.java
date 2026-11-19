package com.medsol.patient;

import com.medsol.audit.AuditService;
import com.medsol.security.MedsolUser;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.UUID;

/** Export du dossier complet d'un patient (médecin, voir SecurityConfig). Inscrit au journal d'accès. */
@Controller
public class ExportDossierController {

    private final ExportDossier export;
    private final AuditService audit;

    public ExportDossierController(ExportDossier export, AuditService audit) {
        this.export = export;
        this.audit = audit;
    }

    @GetMapping("/patients/{id}/export")
    public void exporter(@PathVariable UUID id, @AuthenticationPrincipal MedsolUser me, HttpServletResponse res) throws IOException {
        String nom = export.nom(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        // écrit dans la requête (le cabinet courant est lié au fil d'exécution : pas de flux asynchrone)
        String fichier = "dossier-" + Normalizer.normalize(nom, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replaceAll("[^A-Za-z0-9]+", "-").replaceAll("(^-|-$)", "").toLowerCase() + "-" + LocalDate.now() + ".zip";
        res.setContentType("application/zip");
        res.setHeader("Content-Disposition", "attachment; filename=\"" + fichier + "\"");
        res.setHeader("Cache-Control", "no-store");
        audit.log("EXPORT_DOSSIER", "patient", id);
        export.ecrire(id, me.fullName(), LocaleContextHolder.getLocale(), res.getOutputStream());
    }
}
