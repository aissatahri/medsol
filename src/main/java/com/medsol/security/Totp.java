package com.medsol.security;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;

/**
 * Double authentification par code à 6 chiffres (TOTP, RFC 6238 : HMAC-SHA1, pas de 30 s), compatible avec Google
 * Authenticator, Microsoft Authenticator, FreeOTP… Une dérive d'un pas (±30 s) est tolérée.
 */
public final class Totp {

    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom HASARD = new SecureRandom();

    private Totp() {}

    /** Clé secrète de 160 bits, en base32 (à saisir ou à scanner dans l'application). */
    public static String nouveauSecret() {
        byte[] b = new byte[20];
        HASARD.nextBytes(b);
        StringBuilder s = new StringBuilder();
        int tampon = 0, bits = 0;
        for (byte x : b) {
            tampon = (tampon << 8) | (x & 0xFF);
            bits += 8;
            while (bits >= 5) {
                s.append(BASE32.charAt((tampon >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) s.append(BASE32.charAt((tampon << (5 - bits)) & 31));
        return s.toString();
    }

    public static boolean verifier(String secret, String code, Instant maintenant) {
        return pas(secret, code, maintenant).isPresent();
    }

    /** Pas de 30 s auquel correspond le code (pour refuser qu'il resserve), ou vide s'il est faux. */
    public static java.util.OptionalLong pas(String secret, String code, Instant maintenant) {
        if (secret == null || code == null) return java.util.OptionalLong.empty();
        String c = code.replaceAll("\\s", "");
        if (!c.matches("\\d{6}")) return java.util.OptionalLong.empty();
        long pas = maintenant.getEpochSecond() / 30;
        for (long d = -1; d <= 1; d++) {
            if (java.security.MessageDigest.isEqual(code(secret, pas + d).getBytes(), c.getBytes())) return java.util.OptionalLong.of(pas + d);
        }
        return java.util.OptionalLong.empty();
    }

    /** Code du pas de 30 s contenant cet instant. */
    public static String code(String secret, Instant instant) { return code(secret, instant.getEpochSecond() / 30); }

    static String code(String secret, long pas) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(base32(secret), "HmacSHA1"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(pas).array());
            int o = h[h.length - 1] & 0x0F;
            int v = ((h[o] & 0x7F) << 24) | ((h[o + 1] & 0xFF) << 16) | ((h[o + 2] & 0xFF) << 8) | (h[o + 3] & 0xFF);
            return String.format("%06d", v % 1_000_000);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] base32(String s) {
        String t = s.replace("=", "").toUpperCase();
        ByteBuffer out = ByteBuffer.allocate(t.length() * 5 / 8);
        int tampon = 0, bits = 0;
        for (char ch : t.toCharArray()) {
            int v = BASE32.indexOf(ch);
            if (v < 0) continue;
            tampon = (tampon << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out.put((byte) (tampon >> (bits - 8)));
                bits -= 8;
            }
        }
        return java.util.Arrays.copyOf(out.array(), out.position());
    }

    /** Adresse otpauth:// lue par les applications d'authentification. */
    public static String uri(String secret, String compte, String emetteur) {
        String e = URLEncoder.encode(emetteur, StandardCharsets.UTF_8).replace("+", "%20");
        return "otpauth://totp/" + e + ":" + URLEncoder.encode(compte, StandardCharsets.UTF_8) + "?secret=" + secret + "&issuer=" + e + "&digits=6&period=30";
    }

    /** QR code en SVG (dessiné par le serveur : aucun script ni service extérieur). */
    public static String qrSvg(String texte) {
        try {
            BitMatrix m = new QRCodeWriter().encode(texte, BarcodeFormat.QR_CODE, 0, 0, Map.of(EncodeHintType.MARGIN, 2));
            StringBuilder d = new StringBuilder();
            for (int y = 0; y < m.getHeight(); y++) {
                for (int x = 0; x < m.getWidth(); x++) {
                    if (m.get(x, y)) d.append('M').append(x).append(' ').append(y).append("h1v1h-1z");
                }
            }
            return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + m.getWidth() + " " + m.getHeight()
                    + "\" width=\"200\" height=\"200\" shape-rendering=\"crispEdges\"><rect width=\"100%\" height=\"100%\" fill=\"#fff\"/><path d=\""
                    + d + "\" fill=\"#000\"/></svg>";
        } catch (com.google.zxing.WriterException e) {
            throw new IllegalStateException(e);
        }
    }
}
