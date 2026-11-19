package com.medsol.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Compte connecté. {@code plateforme} : administrateur de MedSol. {@code versionSession} : comparée à chaque requête
 * (un changement de mot de passe, une désactivation ou un changement de droits ferme toutes les sessions).
 * {@code droits} : ce qu'une assistante peut faire en plus du socle (CAISSE, RESULTATS) ; le médecin peut tout.
 */
public record MedsolUser(UUID id, UUID cabinetId, String email, String passwordHash, String fullName,
                         String role, boolean enabled, Instant lockedUntil, boolean plateforme,
                         int versionSession, boolean totp, Set<String> droits, String langue) implements UserDetails {

    public static final List<String> DROITS = List.of("CAISSE", "RESULTATS");

    public MedsolUser(UUID id, UUID cabinetId, String email, String passwordHash, String fullName,
                      String role, boolean enabled, Instant lockedUntil) {
        this(id, cabinetId, email, passwordHash, fullName, role, enabled, lockedUntil, false);
    }

    public MedsolUser(UUID id, UUID cabinetId, String email, String passwordHash, String fullName,
                      String role, boolean enabled, Instant lockedUntil, boolean plateforme) {
        this(id, cabinetId, email, passwordHash, fullName, role, enabled, lockedUntil, plateforme, 0, false, Set.copyOf(DROITS), "fr");
    }

    public boolean medecin() { return "MEDECIN".equals(role); }

    public MedsolUser avec(String nom, int version, boolean totpActif, String lang) {
        return new MedsolUser(id, cabinetId, email, passwordHash, nom, role, enabled, lockedUntil, plateforme, version, totpActif, droits, lang);
    }

    /** Le médecin a tous les droits ; une assistante, ceux que le médecin lui a donnés. */
    public boolean peut(String droit) { return medecin() || droits.contains(droit); }

    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        List<GrantedAuthority> l = new ArrayList<>(List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        if (plateforme) l.add(new SimpleGrantedAuthority("ROLE_PLATEFORME"));
        for (String d : DROITS) if (peut(d)) l.add(new SimpleGrantedAuthority("DROIT_" + d));
        return l;
    }
    @Override public String getPassword() { return passwordHash; }
    @Override public String getUsername() { return email; }
    @Override public boolean isAccountNonLocked() { return lockedUntil == null || lockedUntil.isBefore(Instant.now()); }
    @Override public boolean isEnabled() { return enabled; }
}
