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
package org.traccar.api.resource;

import org.traccar.api.BaseResource;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.PushSubscription;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Web Push subscriptions of the current user: the browser asks for the public key, subscribes with it and
 * registers the subscription here; it can be removed again to stop notifications on that device.
 */
@Path("push")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PushResource extends BaseResource {

    public static class SubscriptionKeys {
        private String p256dh;
        private String auth;

        public String getP256dh() {
            return p256dh;
        }

        public void setP256dh(String p256dh) {
            this.p256dh = p256dh;
        }

        public String getAuth() {
            return auth;
        }

        public void setAuth(String auth) {
            this.auth = auth;
        }
    }

    /** Subscription as produced by the browser's PushSubscription.toJSON(). */
    public static class Subscription {
        private String endpoint;
        private SubscriptionKeys keys;

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public SubscriptionKeys getKeys() {
            return keys;
        }

        public void setKeys(SubscriptionKeys keys) {
            this.keys = keys;
        }
    }

    @Inject
    private Config config;

    @Path("key")
    @GET
    public Map<String, String> getKey() {
        String publicKey = config.getString(Keys.NOTIFICATOR_WEBPUSH_PUBLIC_KEY);
        if (publicKey == null) {
            throw new WebApplicationException(Response.status(Response.Status.NOT_FOUND).build());
        }
        return Map.of("publicKey", publicKey);
    }

    @Path("subscriptions")
    @POST
    public Response subscribe(Subscription subscription) throws StorageException {
        if (subscription == null || subscription.getEndpoint() == null || subscription.getKeys() == null
                || subscription.getKeys().getP256dh() == null || subscription.getKeys().getAuth() == null
                || !subscription.getEndpoint().startsWith("https://")) {
            throw new IllegalArgumentException("Invalid push subscription");
        }
        // An endpoint belongs to one browser; re-subscribing (or another user on the same browser) replaces it
        storage.removeObject(PushSubscription.class, new Request(
                new Condition.Equals("endpoint", subscription.getEndpoint())));

        PushSubscription item = new PushSubscription();
        item.setUserId(getUserId());
        item.setEndpoint(subscription.getEndpoint());
        item.setP256dh(subscription.getKeys().getP256dh());
        item.setAuth(subscription.getKeys().getAuth());
        item.setCreatedTime(new Date());
        item.setId(storage.addObject(item, new Request(new Columns.Exclude("id"))));
        return Response.noContent().build();
    }

    @Path("unsubscribe")
    @POST
    public Response unsubscribe(Subscription subscription) throws StorageException {
        if (subscription != null && subscription.getEndpoint() != null) {
            storage.removeObject(PushSubscription.class, new Request(
                    Condition.merge(List.of(
                            new Condition.Equals("endpoint", subscription.getEndpoint()),
                            new Condition.Equals("userId", getUserId())))));
        }
        return Response.noContent().build();
    }

}
