package org.traccar.notificators;

import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class NotificatorWebPushTest {

    /** Push services reject messages without these headers (Apple: 400 BadWebPushRequest). */
    @Test
    public void testRequestHeaders() {
        HttpRequest request = NotificatorWebPush.buildRequest(
                "https://web.push.apple.com/abc", new byte[100], "vapid t=token, k=key");

        assertEquals("POST", request.method());
        assertEquals("aes128gcm", request.headers().firstValue("Content-Encoding").orElse(null));
        assertEquals("application/octet-stream", request.headers().firstValue("Content-Type").orElse(null));
        assertEquals("86400", request.headers().firstValue("TTL").orElse(null));
        assertEquals("vapid t=token, k=key", request.headers().firstValue("Authorization").orElse(null));
        assertTrue(request.bodyPublisher().isPresent());
        assertEquals(100, request.bodyPublisher().get().contentLength());
    }

}
