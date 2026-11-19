package com.medsol.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Envoi des e-mails de MedSol (mot de passe oublié, invitations). Par SMTP dès que MEDSOL_MAIL_HOST est configuré ;
 * sinon (développement) le message est écrit dans le journal de l'application et gardé pour les tests.
 */
@Service
public class Messagerie {

    private static final Logger LOG = LoggerFactory.getLogger(Messagerie.class);

    public record Mail(String a, String sujet, String texte) {}

    private final ObjectProvider<JavaMailSender> envoi;
    private final String hote;
    private final String expediteur;
    private volatile Mail dernier;

    public Messagerie(ObjectProvider<JavaMailSender> envoi, @Value("${spring.mail.host:}") String hote,
                      @Value("${medsol.mail.from:no-reply@medsol.app}") String expediteur) {
        this.envoi = envoi;
        this.hote = hote;
        this.expediteur = expediteur;
    }

    public void envoyer(String a, String sujet, String texte) {
        dernier = new Mail(a, sujet, texte);
        JavaMailSender s = hote.isBlank() ? null : envoi.getIfAvailable();
        if (s == null) {
            LOG.info("E-mail (développement, aucun serveur SMTP configuré) à {} — {}\n{}", a, sujet, texte);
            return;
        }
        SimpleMailMessage m = new SimpleMailMessage();
        m.setFrom(expediteur);
        m.setTo(a);
        m.setSubject(sujet);
        m.setText(texte);
        s.send(m);
    }

    /** Dernier message (développement et tests). */
    public Mail dernier() { return dernier; }
}
