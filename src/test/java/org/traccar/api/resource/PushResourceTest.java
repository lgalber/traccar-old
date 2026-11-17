package org.traccar.api.resource;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class PushResourceTest {

    /** A real browser subscription (PushSubscription.toJSON()) includes fields the server does not use. */
    @Test
    public void testParseBrowserSubscription() throws Exception {
        ObjectMapper mapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        String json = "{\"endpoint\":\"https://web.push.apple.com/abc\",\"expirationTime\":null,"
                + "\"keys\":{\"p256dh\":\"BKey\",\"auth\":\"secret\",\"extra\":1}}";

        PushResource.Subscription subscription = mapper.readValue(json, PushResource.Subscription.class);

        assertEquals("https://web.push.apple.com/abc", subscription.getEndpoint());
        assertEquals("BKey", subscription.getKeys().getP256dh());
        assertEquals("secret", subscription.getKeys().getAuth());
    }

}
