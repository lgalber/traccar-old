/*
 * Copyright 2026 Luis Albertinazzi
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.helper;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Web Push message encryption (RFC 8291, aes128gcm content coding of RFC 8188) and VAPID authorization
 * (RFC 8292), using only the JDK.
 */
public final class WebPush {

    private static final int RECORD_SIZE = 4096;
    private static final SecureRandom RANDOM = new SecureRandom();

    private WebPush() {
    }

    public static byte[] decodeBase64Url(String value) {
        return Base64.getUrlDecoder().decode(value);
    }

    public static String encodeBase64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static ECParameterSpec curve() throws GeneralSecurityException {
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp256r1"));
        return parameters.getParameterSpec(ECParameterSpec.class);
    }

    /** Public key from its uncompressed point encoding (0x04 || X || Y). */
    public static ECPublicKey publicKey(byte[] point) throws GeneralSecurityException {
        if (point.length != 65 || point[0] != 4) {
            throw new GeneralSecurityException("Invalid P-256 public key");
        }
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(point, 1, 33));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(point, 33, 65));
        return (ECPublicKey) KeyFactory.getInstance("EC")
                .generatePublic(new ECPublicKeySpec(new ECPoint(x, y), curve()));
    }

    /** Private key from its 32-byte scalar. */
    public static ECPrivateKey privateKey(byte[] scalar) throws GeneralSecurityException {
        return (ECPrivateKey) KeyFactory.getInstance("EC")
                .generatePrivate(new ECPrivateKeySpec(new BigInteger(1, scalar), curve()));
    }

    /** Uncompressed point encoding (0x04 || X || Y) of a public key. */
    public static byte[] encodePoint(ECPublicKey key) {
        byte[] result = new byte[65];
        result[0] = 4;
        copyUnsigned(key.getW().getAffineX(), result, 1);
        copyUnsigned(key.getW().getAffineY(), result, 33);
        return result;
    }

    private static void copyUnsigned(BigInteger value, byte[] target, int offset) {
        byte[] bytes = value.toByteArray();
        int length = Math.min(bytes.length, 32);
        System.arraycopy(bytes, bytes.length - length, target, offset + 32 - length, length);
    }

    public static KeyPair generateKeyPair() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
        return generator.generateKeyPair();
    }

    static byte[] hmac(byte[] key, byte[]... data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        for (byte[] part : data) {
            mac.update(part);
        }
        return mac.doFinal();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            output.writeBytes(part);
        }
        return output.toByteArray();
    }

    private static byte[] info(String label) {
        return concat(label.getBytes(StandardCharsets.US_ASCII), new byte[] {0});
    }

    /**
     * Encrypts a payload for a subscription (single aes128gcm record).
     *
     * @param payload plain text message
     * @param userPublicKey subscription "p256dh" key (uncompressed point)
     * @param authSecret subscription "auth" secret (16 bytes)
     */
    public static byte[] encrypt(byte[] payload, byte[] userPublicKey, byte[] authSecret)
            throws GeneralSecurityException {
        return encrypt(payload, userPublicKey, authSecret, generateKeyPair(), randomBytes(16));
    }

    static byte[] encrypt(
            byte[] payload, byte[] userPublicKey, byte[] authSecret, KeyPair serverKeys, byte[] salt)
            throws GeneralSecurityException {

        byte[] serverPublicKey = encodePoint((ECPublicKey) serverKeys.getPublic());

        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(serverKeys.getPrivate());
        agreement.doPhase(publicKey(userPublicKey), true);
        byte[] sharedSecret = agreement.generateSecret();

        // RFC 8291 section 3.4: combine the ECDH secret with the authentication secret
        byte[] prkKey = hmac(authSecret, sharedSecret);
        byte[] keyInfo = concat(info("WebPush: info"), userPublicKey, serverPublicKey);
        byte[] ikm = hmac(prkKey, keyInfo, new byte[] {1});

        // RFC 8188 section 2.2: content encryption key and nonce
        byte[] prk = hmac(salt, ikm);
        byte[] contentKey = Arrays.copyOf(hmac(prk, info("Content-Encoding: aes128gcm"), new byte[] {1}), 16);
        byte[] nonce = Arrays.copyOf(hmac(prk, info("Content-Encoding: nonce"), new byte[] {1}), 12);

        // Single, final record: payload followed by the 0x02 padding delimiter
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(contentKey, "AES"), new GCMParameterSpec(128, nonce));
        byte[] ciphertext = cipher.doFinal(concat(payload, new byte[] {2}));

        ByteBuffer header = ByteBuffer.allocate(16 + 4 + 1 + serverPublicKey.length);
        header.put(salt).putInt(RECORD_SIZE).put((byte) serverPublicKey.length).put(serverPublicKey);
        return concat(header.array(), ciphertext);
    }

    private static byte[] randomBytes(int length) {
        byte[] result = new byte[length];
        RANDOM.nextBytes(result);
        return result;
    }

    /**
     * VAPID "Authorization" header value for a push endpoint.
     *
     * @param endpoint subscription endpoint URL
     * @param publicKey VAPID public key (base64url point)
     * @param privateKey VAPID private key (base64url scalar)
     * @param subject contact URI (mailto: or https:)
     */
    public static String vapidAuthorization(
            String endpoint, String publicKey, String privateKey, String subject, long nowSeconds)
            throws GeneralSecurityException {
        URI uri = URI.create(endpoint);
        String audience = uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        String header = encodeBase64Url("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
        String claims = encodeBase64Url(String.format(
                "{\"aud\":\"%s\",\"exp\":%d,\"sub\":\"%s\"}", audience, nowSeconds + 12 * 3600, subject)
                .getBytes(StandardCharsets.UTF_8));
        String unsigned = header + "." + claims;

        Signature signature = Signature.getInstance("SHA256withECDSAinP1363Format");
        signature.initSign(privateKey(decodeBase64Url(privateKey)));
        signature.update(unsigned.getBytes(StandardCharsets.US_ASCII));
        String token = unsigned + "." + encodeBase64Url(signature.sign());

        return "vapid t=" + token + ", k=" + publicKey;
    }

}
