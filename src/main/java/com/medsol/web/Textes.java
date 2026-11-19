package com.medsol.web;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/**
 * Messages produits par le code (confirmations, erreurs) dans la langue de l'utilisateur : clés des fichiers
 * messages*.properties, paramètres au format MessageFormat ({0}, {1}...).
 */
@Component
public class Textes {

    private final MessageSource messages;

    public Textes(MessageSource messages) { this.messages = messages; }

    public String t(String cle, Object... args) {
        return messages.getMessage(cle, args, LocaleContextHolder.getLocale());
    }
}
