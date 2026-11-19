package com.medsol.abonnement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;

/**
 * Les comptes de MEDSOL_PLATFORM_ADMINS administrent la plateforme : leur cabinet devient « interne » (hors des
 * clients, des compteurs et des rappels ; abonnement illimité, jamais en lecture seule). Fait au démarrage (après les
 * données de démonstration) et à chaque connexion d'un administrateur (compte créé après le démarrage).
 */
@Component
@Order(1000)
public class ComptesPlateforme implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(ComptesPlateforme.class);

    private final JdbcTemplate plateforme;
    private final String[] administrateurs;

    public ComptesPlateforme(@Qualifier("plateforme") JdbcTemplate plateforme, @Value("${medsol.platform-admins:}") String administrateurs) {
        this.plateforme = plateforme;
        this.administrateurs = Arrays.stream(administrateurs.split(",")).map(s -> s.strip().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty()).toArray(String[]::new);
    }

    @Override
    public void run(ApplicationArguments args) {
        for (String email : administrateurs) marquer(email);
    }

    /** Cabinet de cet administrateur : interne, abonnement illimité. Sans effet s'il l'est déjà. */
    public void marquer(String email) {
        int n = plateforme.update("""
                UPDATE cabinet c SET interne = true FROM utilisateur u
                WHERE u.cabinet_id = c.id AND lower(u.email) = lower(?) AND NOT c.interne""", email);
        plateforme.update("""
                UPDATE abonnement a SET statut = 'ACTIF', fin = DATE '2099-12-31', modifie_le = now()
                FROM cabinet c WHERE c.id = a.cabinet_id AND c.interne AND a.fin < DATE '2099-12-31'""");
        if (n > 0) LOG.info("Cabinet de l'administrateur de la plateforme marqué interne");
    }
}
