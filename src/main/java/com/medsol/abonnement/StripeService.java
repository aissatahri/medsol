package com.medsol.abonnement;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Paiement en ligne de l'abonnement par Stripe Checkout (Europe, États-Unis…), sans bibliothèque : création de la
 * session de paiement par l'API REST, puis rappel signé (webhook) qui prolonge l'abonnement. Désactivé tant que les
 * clés ne sont pas configurées (MEDSOL_STRIPE_SECRET_KEY, MEDSOL_STRIPE_WEBHOOK_SECRET).
 */
@Service
public class StripeService {

    /** Devises sans centimes chez Stripe. */
    private static final Set<String> SANS_DECIMALES = Set.of("XOF", "XAF", "JPY", "KRW", "GNF", "RWF", "UGX", "BIF", "DJF", "KMF");
    private static final long TOLERANCE_SECONDES = 300;

    public record Paiement(UUID cabinetId, String periode, BigDecimal montant, String devise, String reference) {}

    private final String cle;
    private final String secretWebhook;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public StripeService(@Value("${medsol.stripe.secret-key:}") String cle,
                         @Value("${medsol.stripe.webhook-secret:}") String secretWebhook) {
        this.cle = cle;
        this.secretWebhook = secretWebhook;
    }

    public boolean actif() { return !cle.isBlank() && !secretWebhook.isBlank(); }

    /** Crée la session de paiement Stripe et renvoie l'adresse où envoyer le médecin. */
    public String session(UUID cabinetId, String periode, BigDecimal montant, String devise, String libelle,
                          String retourSucces, String retourAnnulation) throws java.io.IOException, InterruptedException {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("mode", "payment");
        p.put("client_reference_id", cabinetId.toString());
        p.put("metadata[periode]", periode);
        p.put("line_items[0][quantity]", "1");
        p.put("line_items[0][price_data][currency]", devise.toLowerCase(Locale.ROOT));
        p.put("line_items[0][price_data][unit_amount]", String.valueOf(montantStripe(montant, devise)));
        p.put("line_items[0][price_data][product_data][name]", libelle);
        p.put("success_url", retourSucces);
        p.put("cancel_url", retourAnnulation);
        String corps = p.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .reduce((a, b) -> a + "&" + b).orElse("");
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.stripe.com/v1/checkout/sessions"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + cle)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(corps)).build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2) throw new IllegalStateException("Stripe a refusé la session (" + r.statusCode() + ")");
        Object url = JsonParserFactory.getJsonParser().parseMap(r.body()).get("url");
        if (url == null) throw new IllegalStateException("Réponse de Stripe sans adresse de paiement");
        return url.toString();
    }

    /**
     * Rappel de Stripe : signature vérifiée (en-tête Stripe-Signature, HMAC-SHA256, horodatage récent), puis
     * paiement extrait d'un « checkout.session.completed » payé. Vide pour les autres événements.
     *
     * @throws SecurityException si la signature est absente, fausse ou trop ancienne
     */
    public Optional<Paiement> rappel(String charge, String signature, Instant maintenant) {
        verifier(charge, signature, maintenant);
        Map<String, Object> evenement = JsonParserFactory.getJsonParser().parseMap(charge);
        if (!"checkout.session.completed".equals(evenement.get("type"))) return Optional.empty();
        @SuppressWarnings("unchecked")
        Map<String, Object> objet = (Map<String, Object>) ((Map<String, Object>) evenement.get("data")).get("object");
        if (!"paid".equals(objet.get("payment_status"))) return Optional.empty();
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) objet.getOrDefault("metadata", Map.of());
        String devise = String.valueOf(objet.get("currency")).toUpperCase(Locale.ROOT);
        long total = ((Number) objet.get("amount_total")).longValue();
        BigDecimal montant = SANS_DECIMALES.contains(devise) ? BigDecimal.valueOf(total) : BigDecimal.valueOf(total, 2);
        return Optional.of(new Paiement(UUID.fromString(String.valueOf(objet.get("client_reference_id"))),
                "ANNUEL".equals(meta.get("periode")) ? "ANNUEL" : "MENSUEL", montant, devise, String.valueOf(objet.get("id"))));
    }

    void verifier(String charge, String signature, Instant maintenant) {
        if (secretWebhook.isBlank() || signature == null) throw new SecurityException("Signature absente");
        String t = null;
        List<String> v1 = new ArrayList<>();
        for (String morceau : signature.split(",")) {
            String[] kv = morceau.strip().split("=", 2);
            if (kv.length != 2) continue;
            if (kv[0].equals("t")) t = kv[1];
            if (kv[0].equals("v1")) v1.add(kv[1]);
        }
        if (t == null || v1.isEmpty()) throw new SecurityException("Signature illisible");
        long horodatage;
        try {
            horodatage = Long.parseLong(t);
        } catch (NumberFormatException e) {
            throw new SecurityException("Signature illisible");
        }
        if (Math.abs(maintenant.getEpochSecond() - horodatage) > TOLERANCE_SECONDES) throw new SecurityException("Signature trop ancienne");
        byte[] attendu = hmac(secretWebhook, t + "." + charge);
        boolean bon = v1.stream().anyMatch(s -> MessageDigest.isEqual(attendu, hex(s)));
        if (!bon) throw new SecurityException("Signature invalide");
    }

    static byte[] hmac(String secret, String texte) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(texte.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hex(String s) {
        try {
            return HexFormat.of().parseHex(s);
        } catch (IllegalArgumentException e) {
            return new byte[0];
        }
    }

    static long montantStripe(BigDecimal montant, String devise) {
        return SANS_DECIMALES.contains(devise.toUpperCase(Locale.ROOT)) ? montant.longValue() : montant.movePointRight(2).longValue();
    }
}
