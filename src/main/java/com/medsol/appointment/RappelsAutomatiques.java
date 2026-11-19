package com.medsol.appointment;

import com.medsol.security.Messagerie;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.MessageSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Rappels automatiques des rendez-vous (réglage Paramètres › Rappels du cabinet) : e-mail au patient qui a une adresse,
 * SMS si un prestataire est configuré, envoyés quelques heures avant (24 h par défaut). Un seul envoi par rendez-vous
 * et par canal (noté dans rdv_rappel, visible dans la liste des rappels). Le message ne dit rien du motif : seulement
 * le cabinet, le jour, l'heure, l'adresse et le téléphone. Cabinet en lecture seule (abonnement échu) : pas d'envoi.
 */
@Component
public class RappelsAutomatiques {

    private static final Logger LOG = LoggerFactory.getLogger(RappelsAutomatiques.class);

    record Rdv(UUID id, UUID cabinet, Instant debut, String prenom, String email, String telephone, String cabinetNom,
               String adresse, String cabinetTel, ZoneId zone, String langue, String pays, boolean email_, boolean sms) {}

    private final JdbcTemplate plateforme;
    private final Messagerie mail;
    private final SmsService sms;
    private final MessageSource messages;

    public RappelsAutomatiques(@Qualifier("plateforme") JdbcTemplate plateforme, Messagerie mail, SmsService sms, MessageSource messages) {
        this.plateforme = plateforme;
        this.mail = mail;
        this.sms = sms;
        this.messages = messages;
    }

    @Scheduled(initialDelayString = "PT2M", fixedDelayString = "PT10M")
    public void planifie() {
        try {
            int n = envoyer();
            if (n > 0) LOG.info("Rappels automatiques de rendez-vous envoyés : {}", n);
        } catch (Exception e) {
            LOG.warn("Rappels automatiques interrompus : {}", e.getMessage());
        }
    }

    /** Envoie les rappels dus ; renvoie le nombre de messages envoyés. */
    public int envoyer() {
        List<Rdv> dus = plateforme.query("""
                SELECT r.id, r.cabinet_id, r.debut, p.prenom, p.email, p.telephone, c.nom, c.adresse, c.telephone, c.fuseau,
                       c.langue, c.pays, c.rappel_auto_email, c.rappel_auto_sms
                FROM rendez_vous r
                JOIN patient p ON p.id = r.patient_id
                JOIN cabinet c ON c.id = r.cabinet_id
                JOIN abonnement a ON a.cabinet_id = c.id
                WHERE (c.rappel_auto_email OR c.rappel_auto_sms)
                  AND r.statut IN ('PLANIFIE','CONFIRME','REPORTE')
                  AND r.debut > now() + interval '1 hour'
                  AND r.debut <= now() + make_interval(hours => c.rappel_heures)
                  AND a.statut <> 'SUSPENDU' AND a.fin >= current_date
                ORDER BY r.debut LIMIT 500""",
                (rs, i) -> new Rdv(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getTimestamp(3).toInstant(),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                        ZoneId.of(rs.getString(10)), rs.getString(11), rs.getString(12), rs.getBoolean(13), rs.getBoolean(14)));
        int envoyes = 0;
        for (Rdv r : dus) {
            Locale l = Locale.forLanguageTag(com.medsol.web.Langues.ou_francais(r.langue()));
            ZonedDateTime d = r.debut().atZone(r.zone());
            String jour = d.format(DateTimeFormatter.ofPattern("EEEE d MMMM", l));
            String heure = d.format(DateTimeFormatter.ofPattern("HH:mm"));
            String tel = r.cabinetTel() == null ? "" : r.cabinetTel();
            String prenom = r.prenom() == null ? "" : r.prenom();
            if (r.email_() && r.email() != null && r.email().contains("@") && reserver(r, "EMAIL_AUTO")) {
                try {
                    String adresse = r.adresse() == null ? "" : r.adresse();
                    mail.envoyer(r.email().strip(),
                            messages.getMessage("rappel.mail.subject", new Object[]{r.cabinetNom(), jour, heure}, l),
                            messages.getMessage("rappel.mail.body", new Object[]{prenom, r.cabinetNom(), jour, heure, adresse, tel}, l));
                    envoyes++;
                } catch (Exception e) {
                    annuler(r, "EMAIL_AUTO");
                    LOG.warn("Rappel par e-mail non envoyé (rendez-vous {}) : {}", r.id(), e.getMessage());
                }
            }
            String numero = RappelsController.international(r.telephone(), r.pays());
            if (r.sms() && sms.actif() && numero != null && reserver(r, "SMS_AUTO")) {
                try {
                    sms.envoyer(numero, messages.getMessage("rappel.sms", new Object[]{r.cabinetNom(), jour, heure, tel}, l));
                    envoyes++;
                } catch (Exception e) {
                    annuler(r, "SMS_AUTO");
                    LOG.warn("Rappel par SMS non envoyé (rendez-vous {}) : {}", r.id(), e.getMessage());
                }
            }
        }
        return envoyes;
    }

    /** Note l'envoi avant de le faire : un seul rappel par canal, même si deux tâches tournaient en même temps. */
    private boolean reserver(Rdv r, String canal) {
        return plateforme.update("""
                INSERT INTO rdv_rappel (cabinet_id, rdv_id, canal) VALUES (?, ?, ?)
                ON CONFLICT (rdv_id, canal) WHERE canal IN ('EMAIL_AUTO','SMS_AUTO') DO NOTHING""",
                r.cabinet(), r.id(), canal) == 1;
    }

    /** Envoi échoué : la note est retirée pour réessayer au prochain passage. */
    private void annuler(Rdv r, String canal) {
        plateforme.update("DELETE FROM rdv_rappel WHERE rdv_id = ? AND canal = ?", r.id(), canal);
    }
}
