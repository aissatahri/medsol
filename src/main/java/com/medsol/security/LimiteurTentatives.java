package com.medsol.security;

import com.medsol.abonnement.AbonnementFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Limitation des tentatives par adresse IP sur les pages publiques sensibles (connexion, code de double
 * authentification, mot de passe oublié, liens reçus par e-mail, inscription) : protège contre les robots qui
 * essaient beaucoup de comptes ou de mots de passe, en plus du blocage par compte (LoginAttempts).
 * <p>Fenêtre fixe, en mémoire : suffisant pour un serveur. Avec plusieurs serveurs, partager les compteurs (Redis...).
 * L'adresse est celle du client vue par le proxy (X-Forwarded-For, server.forward-headers-strategy=framework) : le
 * proxy doit remplacer cet en-tête, jamais le recopier depuis le client.
 */
@Component
public class LimiteurTentatives extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(LimiteurTentatives.class);

    private record Regle(String nom, int max, Duration fenetre, String retour) {}

    private static final class Fenetre {
        final long debut;
        int n;
        Fenetre(long debut) { this.debut = debut; }
    }

    private final ConcurrentHashMap<String, Fenetre> compteurs = new ConcurrentHashMap<>();
    private final AtomicLong appels = new AtomicLong();
    private final boolean actif;
    private final Regle connexion, code, oubli, jeton, inscription, reservation;

    public LimiteurTentatives(@Value("${medsol.security.rate-limit.enabled:true}") boolean actif,
                              @Value("${medsol.security.rate-limit.login:20}") int connexion,
                              @Value("${medsol.security.rate-limit.code:10}") int code,
                              @Value("${medsol.security.rate-limit.forgot:5}") int oubli,
                              @Value("${medsol.security.rate-limit.token:20}") int jeton,
                              @Value("${medsol.security.rate-limit.register:5}") int inscription) {
        this.actif = actif;
        this.connexion = new Regle("connexion", connexion, Duration.ofMinutes(15), "/login?trop");
        this.code = new Regle("code", code, Duration.ofMinutes(15), "/2fa?trop");
        this.oubli = new Regle("oubli", oubli, Duration.ofHours(1), "/mot-de-passe-oublie?trop");
        this.jeton = new Regle("jeton", jeton, Duration.ofMinutes(15), null);
        this.inscription = new Regle("inscription", inscription, Duration.ofHours(1), "/register?trop");
        // demandes de rendez-vous en ligne (et annulations) : 10 par heure et par adresse
        this.reservation = new Regle("reservation", 10, Duration.ofHours(1), null);
    }

    /**
     * Compte une tentative. @return les secondes à attendre si la limite est dépassée, 0 sinon.
     */
    public long tenter(String regle, String cle, int max, Duration fenetre) {
        if (!actif) return 0;
        long maintenant = System.currentTimeMillis();
        if (appels.incrementAndGet() % 1000 == 0) {
            compteurs.entrySet().removeIf(e -> maintenant - e.getValue().debut > Duration.ofHours(2).toMillis());
        }
        Fenetre f = compteurs.compute(regle + "|" + cle, (k, v) -> {
            Fenetre w = v == null || maintenant - v.debut >= fenetre.toMillis() ? new Fenetre(maintenant) : v;
            w.n++;
            return w;
        });
        return f.n > max ? Math.max(1, (f.debut + fenetre.toMillis() - maintenant) / 1000) : 0;
    }

    /** Tests : repart de zéro. */
    public void reinitialiser() { compteurs.clear(); }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return !"POST".equals(req.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String p = AbonnementFilter.chemin(req);
        Regle r = switch (p) {
            case "/login" -> connexion;
            case "/2fa" -> code;
            case "/mot-de-passe-oublie" -> oubli;
            case "/register" -> inscription;
            default -> p.startsWith("/reinitialisation/") || p.startsWith("/invitation/") ? jeton : p.startsWith("/r/") ? reservation : null;
        };
        if (r == null) {
            chain.doFilter(req, res);
            return;
        }
        long attente = tenter(r.nom(), req.getRemoteAddr(), r.max(), r.fenetre());
        if (attente == 0) {
            chain.doFilter(req, res);
            return;
        }
        LOG.warn("Trop de tentatives ({}) depuis {} : bloqué {} s", r.nom(), req.getRemoteAddr(), attente);
        res.setHeader("Retry-After", Long.toString(attente));
        if (r.retour() != null) {
            res.sendRedirect(req.getContextPath() + r.retour());
        } else {
            res.sendError(429);
        }
    }
}
