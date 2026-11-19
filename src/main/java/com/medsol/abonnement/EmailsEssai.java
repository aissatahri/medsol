package com.medsol.abonnement;

import com.medsol.security.Messagerie;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Accompagnement de l'essai par e-mail au médecin : J+1 premiers réglages, J+3 ordonnances et impression, J+7 rendez-vous
 * en ligne, rappels et parrainage. Un seul envoi par étape (table essai_email) ; pas de rattrapage au-delà de deux jours
 * (un cabinet inscrit avant cette fonction ne reçoit pas toute la série d'un coup). Les fins d'essai sont rappelées par
 * RappelsAbonnement.
 */
@Component
public class EmailsEssai {

    private static final Logger LOG = LoggerFactory.getLogger(EmailsEssai.class);
    private static final ZoneId MAROC = ZoneId.of("Africa/Casablanca");
    private static final Map<String, Integer> ETAPES = Map.of("J1", 1, "J3", 3, "J7", 7);

    private final JdbcTemplate plateforme;
    private final Messagerie mail;
    private final MessageSource messages;
    private final Editeur editeur;
    private final String baseUrl;

    public EmailsEssai(@Qualifier("plateforme") JdbcTemplate plateforme, Messagerie mail, MessageSource messages, Editeur editeur,
                       @Value("${medsol.base-url:}") String baseUrl) {
        this.plateforme = plateforme;
        this.mail = mail;
        this.messages = messages;
        this.editeur = editeur;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    /** Chaque jour à 10 h (heure du Maroc). */
    @Scheduled(cron = "0 0 10 * * *", zone = "Africa/Casablanca")
    public void planifie() {
        try {
            int n = envoyer(LocalDate.now(MAROC));
            if (n > 0) LOG.info("E-mails d'accompagnement de l'essai envoyés : {}", n);
        } catch (Exception e) {
            LOG.warn("E-mails d'accompagnement interrompus : {}", e.getMessage());
        }
    }

    public int envoyer(LocalDate aujourdhui) {
        record Cabinet(UUID id, long jours, String code) {}
        List<Cabinet> essais = plateforme.query("""
                SELECT c.id, ?::date - (c.cree_le AT TIME ZONE 'Africa/Casablanca')::date, c.code_parrainage
                FROM cabinet c JOIN abonnement a ON a.cabinet_id = c.id
                WHERE a.statut = 'ESSAI' AND NOT c.interne AND a.fin >= ?::date""",
                (rs, i) -> new Cabinet(rs.getObject(1, UUID.class), rs.getLong(2), rs.getString(3)), aujourdhui, aujourdhui);
        var e = editeur.tous();
        String contact = String.join(" · ", java.util.stream.Stream.of(e.get("telephone"), e.get("email")).filter(s -> !s.isBlank()).toList());
        int envoyes = 0;
        for (Cabinet c : essais) {
            for (var etape : ETAPES.entrySet()) {
                long ecart = c.jours() - etape.getValue();
                if (ecart < 0 || ecart > 2) continue;
                if (plateforme.update("INSERT INTO essai_email (cabinet_id, etape) VALUES (?, ?) ON CONFLICT DO NOTHING", c.id(), etape.getKey()) == 0) continue;
                List<Object[]> medecins = plateforme.query("""
                        SELECT email, nom_complet, langue FROM utilisateur WHERE cabinet_id = ? AND role = 'MEDECIN' AND actif""",
                        (rs, i) -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3)}, c.id());
                for (Object[] m : medecins) {
                    Locale l = Locale.forLanguageTag(com.medsol.web.Langues.ou_francais((String) m[2]));
                    String cle = "trial." + etape.getKey().toLowerCase(Locale.ROOT);
                    String lienParrainage = baseUrl + "/register?parrain=" + c.code();
                    try {
                        mail.envoyer((String) m[0], messages.getMessage(cle + ".subject", null, l),
                                messages.getMessage(cle + ".body", new Object[]{m[1], baseUrl, contact.isBlank() ? "—" : contact, lienParrainage}, l));
                        envoyes++;
                    } catch (Exception ex) {
                        LOG.warn("E-mail d'essai {} non envoyé au cabinet {} : {}", etape.getKey(), c.id(), ex.getMessage());
                    }
                }
            }
        }
        return envoyes;
    }
}
