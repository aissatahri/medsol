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
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Rappels par e-mail au médecin : 7 jours et 1 jour avant la fin de l'essai ou de l'abonnement, puis le lendemain de
 * l'échéance (cabinet passé en lecture seule). Un seul envoi par échéance et par nature (table abonnement_rappel).
 */
@Component
public class RappelsAbonnement {

    private static final Logger LOG = LoggerFactory.getLogger(RappelsAbonnement.class);
    private static final ZoneId MAROC = ZoneId.of("Africa/Casablanca");

    record Echeance(UUID cabinet, LocalDate fin, String statut, long jours) {
        String nature() { return jours == 7 ? "J7" : jours == 1 ? "J1" : "ECHU"; }
    }

    private final JdbcTemplate plateforme;
    private final Messagerie mail;
    private final MessageSource messages;
    private final String baseUrl;

    public RappelsAbonnement(@Qualifier("plateforme") JdbcTemplate plateforme, Messagerie mail, MessageSource messages,
                             @Value("${medsol.base-url:}") String baseUrl) {
        this.plateforme = plateforme;
        this.mail = mail;
        this.messages = messages;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** Chaque jour à 9 h (heure du Maroc). */
    @Scheduled(cron = "0 0 9 * * *", zone = "Africa/Casablanca")
    public void planifie() {
        try {
            int n = envoyer(LocalDate.now(MAROC));
            if (n > 0) LOG.info("Rappels d'abonnement envoyés : {}", n);
        } catch (Exception e) {
            LOG.warn("Rappels d'abonnement interrompus : {}", e.getMessage());
        }
    }

    /** Envoie les rappels du jour ; renvoie le nombre d'e-mails envoyés. */
    public int envoyer(LocalDate aujourdhui) {
        List<Echeance> echeances = plateforme.query("""
                SELECT a.cabinet_id, a.fin, a.statut, a.fin - ?::date FROM abonnement a JOIN cabinet c ON c.id = a.cabinet_id
                WHERE NOT c.interne AND a.statut <> 'SUSPENDU' AND a.fin - ?::date IN (7, 1, -1)""",
                (rs, i) -> new Echeance(rs.getObject(1, UUID.class), rs.getObject(2, LocalDate.class), rs.getString(3), rs.getLong(4)),
                aujourdhui, aujourdhui);
        int envoyes = 0;
        for (Echeance e : echeances) {
            int note = plateforme.update("INSERT INTO abonnement_rappel (cabinet_id, fin, nature) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                    e.cabinet(), e.fin(), e.nature());
            if (note == 0) continue;   // déjà envoyé
            List<Object[]> medecins = plateforme.query("""
                    SELECT email, nom_complet, langue FROM utilisateur WHERE cabinet_id = ? AND role = 'MEDECIN' AND actif""",
                    (rs, i) -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3)}, e.cabinet());
            for (Object[] m : medecins) {
                Locale l = Locale.forLanguageTag(com.medsol.web.Langues.ou_francais((String) m[2]));
                String quoi = messages.getMessage("ESSAI".equals(e.statut()) ? "sub.mail.trial" : "sub.mail.subscription", null, l);
                String fin = e.fin().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
                String cle = "sub.mail." + e.nature().toLowerCase(Locale.ROOT);
                try {
                    mail.envoyer((String) m[0], messages.getMessage(cle + ".subject", new Object[]{quoi}, l),
                            messages.getMessage(cle + ".body", new Object[]{m[1], quoi, fin, baseUrl + "/abonnement"}, l));
                    envoyes++;
                } catch (Exception ex) {
                    LOG.warn("Rappel d'abonnement non envoyé au cabinet {} : {}", e.cabinet(), ex.getMessage());
                }
            }
        }
        return envoyes;
    }
}
