package org.traccar.helper;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WebPushTest {

    private static byte[] b64(String value) {
        return WebPush.decodeBase64Url(value);
    }

    /** Example from RFC 8291, Appendix A. */
    @Test
    public void testEncryptRfc8291Example() throws Exception {
        byte[] plaintext = "When I grow up, I want to be a watermelon".getBytes(StandardCharsets.UTF_8);
        byte[] authSecret = b64("BTBZMqHH6r4Tts7J_aSIgg");
        byte[] receiverPublic = b64(
                "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4");
        KeyPair senderKeys = new KeyPair(
                WebPush.publicKey(b64(
                        "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8")),
                WebPush.privateKey(b64("yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw")));
        byte[] salt = b64("DGv6ra1nlYgDCS1FRnbzlw");

        byte[] body = WebPush.encrypt(plaintext, receiverPublic, authSecret, senderKeys, salt);

        assertEquals(
                "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN",
                WebPush.encodeBase64Url(body));
    }

    @Test
    public void testVapidAuthorization() throws Exception {
        KeyPair keys = WebPush.generateKeyPair();
        String publicKey = WebPush.encodeBase64Url(WebPush.encodePoint((ECPublicKey) keys.getPublic()));
        byte[] scalar = new byte[32];
        byte[] raw = ((ECPrivateKey) keys.getPrivate()).getS().toByteArray();
        int length = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - length, scalar, 32 - length, length);

        String header = WebPush.vapidAuthorization(
                "https://fcm.googleapis.com/fcm/send/abc", publicKey, WebPush.encodeBase64Url(scalar),
                "mailto:test@example.com", 1_000_000);

        assertTrue(header.startsWith("vapid t="));
        assertTrue(header.endsWith(", k=" + publicKey));
        String token = header.substring("vapid t=".length(), header.indexOf(", k="));
        String[] parts = token.split("\\.");
        assertEquals(3, parts.length);

        String claims = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        assertEquals("{\"aud\":\"https://fcm.googleapis.com\",\"exp\":1043200,\"sub\":\"mailto:test@example.com\"}",
                claims);

        Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(keys.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertTrue(verifier.verify(Base64.getUrlDecoder().decode(parts[2])));
    }

}
