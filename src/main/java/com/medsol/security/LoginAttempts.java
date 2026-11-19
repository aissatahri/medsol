package com.medsol.security;

import com.medsol.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.UUID;

/**
 * Blocage temporaire après N échecs de connexion ; remise à zéro au succès. Connexions et échecs (sur un compte
 * existant) inscrits au journal d'accès du cabinet.
 */
@Component
public class LoginAttempts {

    private final JdbcTemplate jdbc;
    private final int max;
    private final int minutes;

    public LoginAttempts(JdbcTemplate jdbc,
                         @Value("${medsol.security.max-failed-logins}") int max,
                         @Value("${medsol.security.lock-minutes}") int minutes) {
        this.jdbc = jdbc;
        this.max = max;
        this.minutes = minutes;
    }

    @EventListener
    public void onFailure(AuthenticationFailureBadCredentialsEvent e) {
        String email = e.getAuthentication().getName();
        jdbc.query("SELECT enregistrer_echec_connexion(?, ?, ?)", rs -> { }, email, max, minutes);
        List<UUID[]> compte = jdbc.query("SELECT id, cabinet_id FROM trouver_compte(?)",
                (rs, i) -> new UUID[]{rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)}, email);
        if (!compte.isEmpty()) journal(compte.get(0)[1], compte.get(0)[0], "ECHEC_CONNEXION");
    }

    @EventListener
    public void onSuccess(AuthenticationSuccessEvent e) {
        if (e.getAuthentication().getPrincipal() instanceof MedsolUser u) {
            jdbc.query("SELECT enregistrer_succes_connexion(?)", rs -> { }, u.email());
            journal(u.cabinetId(), u.id(), "CONNEXION");
        }
    }

    private void journal(UUID cabinet, UUID utilisateur, String action) {
        String ip = RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a
                ? ((HttpServletRequest) a.getRequest()).getRemoteAddr() : null;
        UUID avant = TenantContext.get();
        TenantContext.set(cabinet);
        try {
            jdbc.update("INSERT INTO journal_audit (cabinet_id, utilisateur_id, action, objet, objet_id, ip) VALUES (?, ?, ?, 'utilisateur', ?, ?)",
                    cabinet, utilisateur, action, utilisateur.toString(), ip);
        } finally {
            if (avant == null) TenantContext.clear(); else TenantContext.set(avant);
        }
    }
}
