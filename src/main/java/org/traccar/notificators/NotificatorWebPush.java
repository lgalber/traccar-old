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
package org.traccar.notificators;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.helper.WebHelper;
import org.traccar.helper.WebPush;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.model.PushSubscription;
import org.traccar.model.User;
import org.traccar.notification.NotificationFormatter;
import org.traccar.notification.NotificationMessage;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Sends notifications as Web Push messages to every browser/device where the user enabled notifications.
 * Subscriptions the push service reports as gone (404/410) are removed.
 */
@Singleton
public class NotificatorWebPush extends Notificator {

    private static final Logger LOGGER = LoggerFactory.getLogger(NotificatorWebPush.class);

    private final Storage storage;
    private final Client client;
    private final ObjectMapper objectMapper;
    private final String publicKey;
    private final String privateKey;
    private final String subject;
    private final String webUrl;

    @Inject
    public NotificatorWebPush(
            Config config, NotificationFormatter notificationFormatter, Storage storage, Client client,
            ObjectMapper objectMapper) {
        super(notificationFormatter);
        this.storage = storage;
        this.client = client;
        this.objectMapper = objectMapper;
        publicKey = config.getString(Keys.NOTIFICATOR_WEBPUSH_PUBLIC_KEY);
        privateKey = config.getString(Keys.NOTIFICATOR_WEBPUSH_PRIVATE_KEY);
        subject = config.getString(Keys.NOTIFICATOR_WEBPUSH_SUBJECT, "mailto:admin@example.com");
        webUrl = config.getString(Keys.WEB_URL);
    }

    @Override
    public CompletableFuture<Void> sendAsync(User user, NotificationMessage message, Event event, Position position) {
        if (publicKey == null || privateKey == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Web Push keys not configured"));
        }
        try {
            List<PushSubscription> subscriptions = storage.getObjects(PushSubscription.class, new Request(
                    new Columns.All(), new Condition.Equals("userId", user.getId())));
            byte[] payload = payload(message, event);
            long now = System.currentTimeMillis() / 1000;
            return CompletableFuture.allOf(subscriptions.stream()
                    .map(subscription -> send(subscription, payload, now))
                    .toArray(CompletableFuture[]::new));
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private byte[] payload(NotificationMessage message, Event event) throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("title", message.subject() != null ? message.subject() : "Traccar");
        data.put("body", message.digest());
        data.put("tag", "event-" + event.getId());
        data.put("deviceId", event.getDeviceId());
        data.put("url", webUrl != null ? webUrl : "./");
        return objectMapper.writeValueAsString(data).getBytes(StandardCharsets.UTF_8);
    }

    private CompletableFuture<Void> send(PushSubscription subscription, byte[] payload, long now) {
        try {
            byte[] body = WebPush.encrypt(
                    payload,
                    WebPush.decodeBase64Url(subscription.getP256dh()),
                    WebPush.decodeBase64Url(subscription.getAuth()));
            var request = client.target(subscription.getEndpoint()).request()
                    .header("TTL", "86400")
                    .header("Urgency", "high")
                    .header("Content-Encoding", "aes128gcm")
                    .header("Authorization", WebPush.vapidAuthorization(
                            subscription.getEndpoint(), publicKey, privateKey, subject, now));
            return WebHelper.post(request, Entity.entity(body, "application/octet-stream"), response -> {
                int status = response.getStatus();
                if (status == Response.Status.NOT_FOUND.getStatusCode()
                        || status == Response.Status.GONE.getStatusCode()) {
                    remove(subscription);
                } else if (status >= 300) {
                    LOGGER.warn("Web Push failed with status {}: {}", status, response.readEntity(String.class));
                }
            });
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private void remove(PushSubscription subscription) {
        try {
            storage.removeObject(PushSubscription.class, new Request(
                    new Condition.Equals("id", subscription.getId())));
        } catch (StorageException e) {
            LOGGER.warn("Failed to remove expired push subscription", e);
        }
    }

}
