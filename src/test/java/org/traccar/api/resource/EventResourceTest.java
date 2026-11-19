package org.traccar.api.resource;

import org.junit.jupiter.api.Test;
import org.traccar.model.Event;
import org.traccar.model.Notification;
import org.traccar.model.Position;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EventResourceTest {

    private static Notification rule(String type, String alarms) {
        Notification rule = new Notification();
        rule.setType(type);
        rule.setNotificators("web");
        if (alarms != null) {
            rule.set("alarms", alarms);
        }
        return rule;
    }

    private static Event event(String type, String alarm) {
        Event event = new Event(type, 1);
        if (alarm != null) {
            event.set(Position.KEY_ALARM, alarm);
        }
        return event;
    }

    @Test
    public void testAlarmMatchesListedAlarmType() {
        assertTrue(EventResource.matches(
                rule(Event.TYPE_ALARM, "vibration,movement"), event(Event.TYPE_ALARM, "vibration")));
    }

    @Test
    public void testAlarmNotListedDoesNotMatch() {
        assertFalse(EventResource.matches(
                rule(Event.TYPE_ALARM, "sos"), event(Event.TYPE_ALARM, "vibration")));
    }

    @Test
    public void testAlarmRuleWithoutAlarmTypesDoesNotMatch() {
        assertFalse(EventResource.matches(
                rule(Event.TYPE_ALARM, null), event(Event.TYPE_ALARM, "vibration")));
    }

    @Test
    public void testOtherTypesMatchByType() {
        assertTrue(EventResource.matches(
                rule(Event.TYPE_DEVICE_MOVING, null), event(Event.TYPE_DEVICE_MOVING, null)));
        assertFalse(EventResource.matches(
                rule(Event.TYPE_DEVICE_MOVING, null), event(Event.TYPE_DEVICE_STOPPED, null)));
    }

    @Test
    public void testMatchesAnyRule() {
        List<Notification> rules = List.of(
                rule(Event.TYPE_DEVICE_MOVING, null), rule(Event.TYPE_ALARM, "vibration"));
        assertTrue(EventResource.matchesAny(rules, event(Event.TYPE_ALARM, "vibration")));
        assertFalse(EventResource.matchesAny(rules, event(Event.TYPE_IGNITION_ON, null)));
    }

    private static Event at(String type, long minutesAgo, long now) {
        Event event = new Event(type, 1);
        event.setEventTime(new Date(now - minutesAgo * 60_000));
        return event;
    }

    @Test
    public void testOldAlarmsStayPendingAndAreNotPushedOut() {
        long now = System.currentTimeMillis();
        Date othersFrom = new Date(now - 7L * 24 * 60 * 60_000);
        List<Event> events = new ArrayList<>();
        events.add(at(Event.TYPE_ALARM, 10L * 24 * 60, now));
        events.add(at(Event.TYPE_DEVICE_MOVING, 10L * 24 * 60, now));
        for (int i = 0; i < EventResource.UNSEEN_LIMIT + 10; i++) {
            events.add(at(Event.TYPE_DEVICE_MOVING, i, now));
        }

        List<Event> pending = EventResource.selectPending(events, othersFrom);

        assertEquals(EventResource.UNSEEN_LIMIT + 1, pending.size());
        assertEquals(1, pending.stream().filter(e -> Event.TYPE_ALARM.equals(e.getType())).count());
        assertEquals(Event.TYPE_ALARM, pending.get(pending.size() - 1).getType());
    }

}
