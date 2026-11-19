package com.medsol.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Chiffrement applicatif des secrets en base (clé de double authentification) : AES-256-GCM, vecteur aléatoire de
 * 96 bits, contexte authentifié (« totp:&lt;id du compte&gt; ») pour qu'une valeur ne puisse pas être recopiée sur un
 * autre compte. La clé (32 octets en base64) vient de MEDSOL_ENCRYPTION_KEY et ne doit jamais être perdue : sans elle,
 * les doubles authentifications devront être réactivées.
 * <p>Format stocké : {@code v1:<base64(vecteur || chiffré || étiquette)>}.
 */
@Service
public class Chiffrement {

    private static final String PREFIXE = "v1:";
    private static final SecureRandom HASARD = new SecureRandom();

    private final SecretKeySpec cle;

    public Chiffrement(@Value("${medsol.encryption-key:}") String cle) {
        if (cle.isBlank()) {
            throw new IllegalStateException("MEDSOL_ENCRYPTION_KEY manquante : générez-la avec « openssl rand -base64 32 » "
                    + "et gardez-en une copie hors du serveur (voir DEPLOIEMENT.md).");
        }
        byte[] octets = Base64.getDecoder().decode(cle.strip());
        if (octets.length != 32) throw new IllegalStateException("MEDSOL_ENCRYPTION_KEY doit faire 32 octets (base64).");
        this.cle = new SecretKeySpec(octets, "AES");
    }

    public static boolean chiffre(String valeur) { return valeur != null && valeur.startsWith(PREFIXE); }

    public String chiffrer(String clair, String contexte) {
        try {
            byte[] iv = new byte[12];
            HASARD.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, cle, new GCMParameterSpec(128, iv));
            c.updateAAD(contexte.getBytes(StandardCharsets.UTF_8));
            byte[] ch = c.doFinal(clair.getBytes(StandardCharsets.UTF_8));
            return PREFIXE + Base64.getEncoder().encodeToString(ByteBuffer.allocate(12 + ch.length).put(iv).put(ch).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Valeur antérieure au chiffrement (en clair) : rendue telle quelle, elle sera chiffrée au démarrage suivant. */
    public String dechiffrer(String valeur, String contexte) {
        if (valeur == null || !chiffre(valeur)) return valeur;
        try {
            byte[] b = Base64.getDecoder().decode(valeur.substring(PREFIXE.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, cle, new GCMParameterSpec(128, b, 0, 12));
            c.updateAAD(contexte.getBytes(StandardCharsets.UTF_8));
            return new String(c.doFinal(b, 12, b.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Secret illisible : clé de chiffrement différente ou valeur altérée", e);
        }
    }
}
