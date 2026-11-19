package com.medsol.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AccountService implements UserDetailsService {

    private final JdbcTemplate jdbc;
    private final Set<String> administrateurs;

    public AccountService(JdbcTemplate jdbc, @Value("${medsol.platform-admins:}") String administrateurs) {
        this.jdbc = jdbc;
        this.administrateurs = Arrays.stream(administrateurs.split(",")).map(s -> s.strip().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        List<MedsolUser> found = jdbc.query("SELECT * FROM trouver_compte(?)", (rs, i) -> new MedsolUser(
                rs.getObject("id", UUID.class),
                rs.getObject("cabinet_id", UUID.class),
                rs.getString("email"),
                rs.getString("mot_de_passe"),
                rs.getString("nom_complet"),
                rs.getString("role"),
                rs.getBoolean("actif"),
                rs.getTimestamp("bloque_jusqua") == null ? null : rs.getTimestamp("bloque_jusqua").toInstant(),
                administrateurs.contains(rs.getString("email").toLowerCase(Locale.ROOT)),
                rs.getInt("version_session"), rs.getBoolean("totp_actif"),
                Set.of((String[]) rs.getArray("droits").getArray()), rs.getString("langue")), email);
        if (found.isEmpty()) {
            throw new UsernameNotFoundException(email);
        }
        return found.get(0);
    }
}
