package com.medsol.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;

import java.util.Locale;

@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    /** Le limiteur ne tourne que dans la chaîne de sécurité (avant la connexion), pas une seconde fois comme filtre global. */
    @Bean
    public org.springframework.boot.web.servlet.FilterRegistrationBean<LimiteurTentatives> limiteurHorsServlet(LimiteurTentatives l) {
        var r = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(l);
        r.setEnabled(false);
        return r;
    }

    @Bean
    public LocaleResolver localeResolver() {
        CookieLocaleResolver r = new CookieLocaleResolver("medsol-lang");
        r.setDefaultLocale(Locale.FRENCH);
        return r;
    }

    @Bean
    public SecurityFilterChain chain(HttpSecurity http, com.medsol.abonnement.AbonnementService abonnements,
                                     com.medsol.cabinet.CabinetService cabinets, org.springframework.jdbc.core.JdbcTemplate jdbc,
                                     LimiteurTentatives limiteur, com.medsol.abonnement.ComptesPlateforme comptesPlateforme,
                                     @org.springframework.beans.factory.annotation.Value("${medsol.security.require-2fa-doctors:false}") boolean exige2fa)
            throws Exception {
        http
            .authorizeHttpRequests(a -> a
                .requestMatchers("/").permitAll()   // page d'accueil publique ; tableau de bord une fois connecté
                .requestMatchers("/login", "/register", "/conditions", "/sous-traitance", "/confidentialite", "/mentions-legales", "/lang", "/css/**", "/js/**", "/img/**", "/favicon.ico", "/error").permitAll()
                // mot de passe oublié et invitation : pages publiques, protégées par un jeton à usage unique
                .requestMatchers("/mot-de-passe-oublie", "/reinitialisation/*", "/invitation/*").permitAll()
                // prise de rendez-vous en ligne par les patients (pages publiques du cabinet)
                .requestMatchers("/r/**").permitAll()
                // rappel de Stripe : public, protégé par sa signature (vérifiée par StripeService)
                .requestMatchers("/stripe/webhook").permitAll()
                // espace administrateur de MedSol (cabinets, abonnements, tarifs)
                .requestMatchers("/plateforme/**", "/plateforme").hasRole("PLATEFORME")
                // dossier médical et paramètres : médecin uniquement (l'assistante n'y accède pas)
                .requestMatchers("/consultations/**", "/settings/**", "/statistiques", "/patients/*/dossier", "/patients/*/dossier/**",
                        "/patients/*/antecedents/**", "/patients/*/allergies/**", "/patients/*/export",
                        "/patients/archives", "/patients/*/restaurer").hasRole("MEDECIN")
                // droits réglables de l'assistante (Paramètres › Utilisateurs) : caisse et factures, fichiers et résultats
                .requestMatchers("/caisse", "/caisse/**", "/patients/*/facture").hasAnyAuthority("ROLE_MEDECIN", "DROIT_CAISSE")
                .requestMatchers("/patients/*/fichiers", "/patients/*/fichiers/**").hasAnyAuthority("ROLE_MEDECIN", "DROIT_RESULTATS")
                .anyRequest().authenticated())
            .formLogin(f -> f.loginPage("/login").permitAll()
                // langue du compte ; double authentification : code demandé avant toute autre page
                .successHandler((req, res, a) -> {
                    MedsolUser u = (MedsolUser) a.getPrincipal();
                    if (u.langue() != null) localeResolver().setLocale(req, res, Locale.forLanguageTag(u.langue()));
                    // administrateur de MedSol : espace Plateforme, cabinet interne (hors clients et abonnement)
                    if (u.plateforme()) comptesPlateforme.marquer(u.email());
                    String cible = u.plateforme() ? "/plateforme" : "/";
                    if (u.totp()) {
                        req.getSession().setAttribute(SessionGuard.CODE_ATTENDU, Boolean.TRUE);
                        cible = "/2fa";
                    } else if (exige2fa && (u.medecin() || u.plateforme())) {
                        req.getSession().setAttribute(SessionGuard.ACTIVATION_REQUISE, Boolean.TRUE);
                        cible = "/compte?2fa";
                    }
                    res.sendRedirect(req.getContextPath() + cible);
                })
                // Message précis : mot de passe faux, compte bloqué (trop d'essais) ou désactivé par le médecin
                .failureHandler((req, res, ex) -> res.sendRedirect(req.getContextPath() + "/login?"
                        + (ex instanceof org.springframework.security.authentication.LockedException ? "bloque"
                        : ex instanceof org.springframework.security.authentication.DisabledException ? "desactive" : "error"))))
            .logout(l -> l.logoutSuccessUrl("/login?logout"))
            .csrf(c -> c.ignoringRequestMatchers("/stripe/webhook"))
            // Jeton CSRF périmé (page ouverte avant un redémarrage, session expirée) : retour à la connexion
            // au lieu d'une page 403 ; les autres refus d'accès gardent le 403 (page d'erreur de l'application).
            .exceptionHandling(e -> e.accessDeniedHandler((req, res, ex) -> {
                if (ex instanceof org.springframework.security.web.csrf.CsrfException) {
                    var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
                    boolean connecte = auth != null && auth.getPrincipal() instanceof MedsolUser;
                    if (!connecte || "/login".equals(com.medsol.abonnement.AbonnementFilter.chemin(req))) {
                        res.sendRedirect(req.getContextPath() + "/login?expire");
                        return;
                    }
                }
                res.sendError(jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN);
            }))
            .headers(h -> h
                .frameOptions(fo -> fo.deny())
                .contentSecurityPolicy(c -> c.policyDirectives(
                    "default-src 'self'; script-src 'self'; img-src 'self' data:; "
                    // styles intégrés permis : le calendrier positionne ses rendez-vous ainsi (scripts : le site seul)
                    + "style-src 'self' 'unsafe-inline'; "
                    + "frame-ancestors 'none'; form-action 'self'; base-uri 'self'; object-src 'none'"))
                .referrerPolicy(r -> r.policy(
                    org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN))
                .addHeaderWriter(new org.springframework.security.web.header.writers.StaticHeadersWriter(
                    "Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()")))
            // trop de tentatives depuis une même adresse IP (connexion, code, mot de passe oublié, liens, inscription)
            .addFilterBefore(limiteur, org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(new TenantFilter(), AnonymousAuthenticationFilter.class)
            // compte désactivé ou mot de passe changé : sessions fermées partout ; double authentification en attente
            .addFilterAfter(new SessionGuard(jdbc), TenantFilter.class)
            // abonnement échu ou suspendu : lecture seule (après le choix du cabinet)
            .addFilterAfter(new com.medsol.abonnement.AbonnementFilter(abonnements, cabinets), SessionGuard.class);
        return http.build();
    }
}
