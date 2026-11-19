package com.medsol.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.Locale;
import java.util.UUID;

/**
 * E-mails portant un lien à usage unique : mot de passe oublié (30 minutes) et invitation d'une assistante (72 heures).
 * L'adresse du site vient de {@code medsol.base-url} en production (jamais de l'en-tête Host, falsifiable).
 */
@Service
public class LiensCompte {

    public static final int REINITIALISATION_MINUTES = 30;
    public static final int INVITATION_MINUTES = 72 * 60;

    private final Jetons jetons;
    private final Messagerie mail;
    private final MessageSource messages;
    private final String baseUrl;

    public LiensCompte(Jetons jetons, Messagerie mail, MessageSource messages, @Value("${medsol.base-url:}") String baseUrl) {
        this.jetons = jetons;
        this.mail = mail;
        this.messages = messages;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public void reinitialisation(Jetons.Compte c) {
        String lien = base() + "/reinitialisation/" + jetons.creer(c.id(), "REINITIALISATION", REINITIALISATION_MINUTES);
        Locale l = langue(c.langue());
        mail.envoyer(c.email(), messages.getMessage("mail.reset.subject", null, l),
                messages.getMessage("mail.reset.body", new Object[]{c.nom(), lien, REINITIALISATION_MINUTES}, l));
    }

    public void invitation(UUID compte, String email, String nom, String langue, String cabinet, String medecin) {
        String lien = base() + "/invitation/" + jetons.creer(compte, "INVITATION", INVITATION_MINUTES);
        Locale l = langue(langue);
        mail.envoyer(email, messages.getMessage("mail.invite.subject", new Object[]{cabinet}, l),
                messages.getMessage("mail.invite.body", new Object[]{nom, medecin, cabinet, lien, INVITATION_MINUTES / 60}, l));
    }

    private String base() {
        return baseUrl.isBlank() ? ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString() : baseUrl;
    }

    private static Locale langue(String code) { return Locale.forLanguageTag(code == null ? "fr" : code); }
}
