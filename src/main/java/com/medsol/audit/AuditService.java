package com.medsol.audit;

import com.medsol.security.MedsolUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

/** Journal d'accès aux données de santé (RGPD art. 30/32, HIPAA §164.312(b)). Ajout seul. */
@Service
public class AuditService {

    private final JdbcTemplate jdbc;

    public AuditService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void log(String action, String object, Object objectId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        MedsolUser user = auth != null && auth.getPrincipal() instanceof MedsolUser u ? u : null;
        String ip = null;
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a) {
            HttpServletRequest req = a.getRequest();
            ip = req.getRemoteAddr();   // X-Forwarded-For pris en compte via forward-headers-strategy
        }
        jdbc.update("""
                INSERT INTO journal_audit (cabinet_id, utilisateur_id, action, objet, objet_id, ip)
                VALUES (current_cabinet(), ?, ?, ?, ?, ?)""",
                user == null ? null : user.id(), action, object, objectId == null ? null : objectId.toString(), ip);
    }
}
