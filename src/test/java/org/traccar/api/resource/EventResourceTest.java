package org.traccar.api.resource;

import org.junit.jupiter.api.Test;
import org.traccar.model.Event;
import org.traccar.model.Notification;
import org.traccar.model.Position;

import java.util.List;

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

}
