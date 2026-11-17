/*
 * Copyright 2016 - 2021 Anton Tananaev (anton@traccar.org)
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
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.EventSeen;
import org.traccar.model.Notification;
import org.traccar.model.Position;
import org.traccar.model.User;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Path("events")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class EventResource extends BaseResource {

    static final int UNSEEN_MAX_DAYS = 30;
    static final int UNSEEN_LIMIT = 50;

    @Path("{id}")
    @GET
    public Event get(@PathParam("id") long id) throws StorageException {
        Event event = storage.getObject(Event.class, new Request(
                new Columns.All(), new Condition.Equals("id", id)));
        if (event == null) {
            throw new WebApplicationException(Response.status(Response.Status.NOT_FOUND).build());
        }
        permissionsService.checkPermission(Device.class, getUserId(), event.getDeviceId());
        return event;
    }

    /**
     * Pending alerts of the current user: events of the user's devices from the last {@code days} days that
     * match one of the user's notification rules with the web channel, and that the user has not marked as seen.
     * Newest first, at most {@link #UNSEEN_LIMIT}.
     */
    @Path("unseen")
    @GET
    public Collection<Event> getUnseen(@QueryParam("days") @DefaultValue("7") int days) throws StorageException {
        long userId = getUserId();

        List<Notification> rules = storage.getObjects(Notification.class, new Request(
                new Columns.All(), new Condition.Permission(User.class, userId, Notification.class)))
                .stream()
                .filter(rule -> rule.getNotificatorsTypes().contains("web"))
                .toList();
        if (rules.isEmpty()) {
            return List.of();
        }

        Set<Long> seenIds = storage.getObjects(EventSeen.class, new Request(
                new Columns.All(), new Condition.Equals("userId", userId)))
                .stream()
                .map(EventSeen::getEventId)
                .collect(Collectors.toSet());

        Date to = new Date();
        Date from = new Date(to.getTime() - Duration.ofDays(Math.clamp(days, 1, UNSEEN_MAX_DAYS)).toMillis());

        List<Event> result = new ArrayList<>();
        for (Device device : storage.getObjects(Device.class, new Request(
                new Columns.All(), new Condition.Permission(User.class, userId, Device.class)))) {

            // Rules apply to all of the user's devices when "always" is set, otherwise to linked devices only
            Set<Long> linkedRuleIds = storage.getObjects(Notification.class, new Request(
                    new Columns.Include("id"),
                    new Condition.Permission(Device.class, device.getId(), Notification.class)))
                    .stream()
                    .map(Notification::getId)
                    .collect(Collectors.toSet());
            List<Notification> deviceRules = rules.stream()
                    .filter(rule -> rule.getAlways() || linkedRuleIds.contains(rule.getId()))
                    .toList();
            if (deviceRules.isEmpty()) {
                continue;
            }

            for (Event event : storage.getObjects(Event.class, new Request(
                    new Columns.All(),
                    Condition.merge(List.of(
                            new Condition.Equals("deviceId", device.getId()),
                            new Condition.Between("eventTime", from, to)))))) {
                if (!seenIds.contains(event.getId()) && matchesAny(deviceRules, event)) {
                    result.add(event);
                }
            }
        }

        result.sort(Comparator.comparing(Event::getEventTime).reversed());
        return result.size() > UNSEEN_LIMIT ? result.subList(0, UNSEEN_LIMIT) : result;
    }

    /**
     * Whether a notification rule covers an event: same type and, for alarms, one of the rule's alarm types
     * (mirrors the matching used when notifications are sent).
     */
    static boolean matches(Notification rule, Event event) {
        if (!rule.getType().equals(event.getType())) {
            return false;
        }
        if (Event.TYPE_ALARM.equals(event.getType())) {
            String alarms = rule.getString("alarms");
            return alarms != null
                    && Arrays.asList(alarms.split(",")).contains(event.getString(Position.KEY_ALARM));
        }
        return true;
    }

    static boolean matchesAny(List<Notification> rules, Event event) {
        return rules.stream().anyMatch(rule -> matches(rule, event));
    }

    @Path("{id}/seen")
    @POST
    public Response markSeen(@PathParam("id") long id) throws StorageException {
        markSeen(getUserId(), get(id));
        return Response.noContent().build();
    }

    /**
     * Marks several events as seen at once. Unknown ids are ignored; events of devices the user cannot access are
     * rejected.
     */
    @Path("seen")
    @POST
    public Response markSeen(List<Long> ids) throws StorageException {
        long userId = getUserId();
        for (long id : ids) {
            Event event = storage.getObject(Event.class, new Request(
                    new Columns.All(), new Condition.Equals("id", id)));
            if (event != null) {
                permissionsService.checkPermission(Device.class, userId, event.getDeviceId());
                markSeen(userId, event);
            }
        }
        return Response.noContent().build();
    }

    private void markSeen(long userId, Event event) throws StorageException {
        EventSeen existing = storage.getObject(EventSeen.class, new Request(
                new Columns.All(),
                new Condition.And(
                        new Condition.Equals("userId", userId),
                        new Condition.Equals("eventId", event.getId()))));
        if (existing == null) {
            EventSeen seen = new EventSeen();
            seen.setUserId(userId);
            seen.setEventId(event.getId());
            seen.setSeenTime(new Date());
            storage.addObject(seen, new Request(new Columns.Exclude("id")));
        }
    }

}
