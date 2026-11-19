package com.medsol.appointment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Envoi de SMS par un prestataire (Twilio pour l'instant : couvre le Maroc et l'Afrique), sans bibliothèque.
 * Inactif tant que MEDSOL_SMS_TWILIO_SID, MEDSOL_SMS_TWILIO_TOKEN et MEDSOL_SMS_FROM ne sont pas configurés.
 * Le texte ne contient jamais de donnée médicale (seulement le cabinet, le jour et l'heure).
 */
@Service
public class SmsService {

    private final String sid;
    private final String token;
    private final String expediteur;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public SmsService(@Value("${medsol.sms.twilio-sid:}") String sid, @Value("${medsol.sms.twilio-token:}") String token,
                      @Value("${medsol.sms.from:}") String expediteur) {
        this.sid = sid.strip();
        this.token = token.strip();
        this.expediteur = expediteur.strip();
    }

    public boolean actif() { return !sid.isEmpty() && !token.isEmpty() && !expediteur.isEmpty(); }

    /** @param international numéro au format international sans « + » (212612345678) */
    public void envoyer(String international, String texte) throws Exception {
        if (!actif()) throw new IllegalStateException("SMS non configuré");
        String corps = "To=" + enc("+" + international) + "&From=" + enc(expediteur) + "&Body=" + enc(texte);
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.twilio.com/2010-04-01/Accounts/" + sid + "/Messages.json"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString((sid + ":" + token).getBytes(StandardCharsets.UTF_8)))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(corps)).build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2) throw new IllegalStateException("SMS refusé par le prestataire (" + r.statusCode() + ")");
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
