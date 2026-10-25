package io.futurecode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

public final class Crypto {
    private static final SecureRandom RANDOM = new SecureRandom();
    private Crypto() {}
    public static String random() { byte[] value = new byte[32]; RANDOM.nextBytes(value); return encode(value); }
    public static String encode(byte[] value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    public static byte[] decode(String value) { return Base64.getUrlDecoder().decode(value); }
    public static byte[] shaBytes(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 is unavailable."); }
    }
    public static String sha(String value) { return HexFormat.of().formatHex(shaBytes(value.getBytes(StandardCharsets.UTF_8))); }
    public static String hmac(String key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return encode(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException error) { throw new IllegalStateException("HMAC-SHA256 is unavailable."); }
    }
    public static boolean equal(String first, String second) {
        return first != null && second != null && MessageDigest.isEqual(first.getBytes(StandardCharsets.UTF_8), second.getBytes(StandardCharsets.UTF_8));
    }
    public static String hmacHex(String key, byte[] value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value));
        } catch (java.security.GeneralSecurityException error) { throw new IllegalStateException("HMAC-SHA256 is unavailable."); }
    }
}
