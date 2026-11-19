package com.medsol;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.core.env.Environment;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le français reste le français sur un serveur dont la langue système est l'anglais (Ubuntu : en_US) : bug vu en
 * production, invisible sur un poste de développement en français.
 */
class LanguesTest extends WebTestSupport {

    @Autowired Environment env;

    @Test
    void frenchDoesNotFallBackToTheServerLanguage() {
        assertThat(env.getProperty("spring.messages.fallback-to-system-locale")).isEqualTo("false");
        Locale avant = Locale.getDefault();
        Locale.setDefault(Locale.US);
        try {
            // source neuve (aucun cache), réglée comme celle de l'application
            ResourceBundleMessageSource m = new ResourceBundleMessageSource();
            m.setBasename("messages");
            m.setDefaultEncoding("UTF-8");
            m.setFallbackToSystemLocale(Boolean.parseBoolean(env.getProperty("spring.messages.fallback-to-system-locale")));
            assertThat(m.getMessage("login.submit", null, Locale.FRENCH)).isEqualTo("Se connecter");
            assertThat(m.getMessage("login.submit", null, Locale.ENGLISH)).isEqualTo("Sign in");
            assertThat(m.getMessage("login.submit", null, Locale.forLanguageTag("es"))).isEqualTo("Iniciar sesión");
            // langue non prise en charge : français
            assertThat(m.getMessage("login.submit", null, Locale.GERMAN)).isEqualTo("Se connecter");
        } finally {
            Locale.setDefault(avant);
        }
    }
}
