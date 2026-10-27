package com.urbantransport.identity.event;

import com.urbantransport.identity.service.DriverSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class FleetEventsListener {

    private final JsonMapper jsonMapper;
    private final DriverSyncService driverSyncService;

    @KafkaListener(topics = "${identity.kafka.fleet-events-topic}")
    public void onFleetEvent(String message) {
        IncomingEvent event = jsonMapper.readValue(message, IncomingEvent.class);
        if (!EventTypes.DRIVER_DEACTIVATED.equals(event.eventType())) {
            log.debug("Evento {} ignorado", event.eventType());
            return;
        }
        Object userId = event.payload().get("userId");
        if (userId == null) {
            log.warn("Evento {} sin userId, no hay cuenta que desactivar", event.eventId());
            return;
        }
        driverSyncService.applyDriverDeactivated(event.eventId(), UUID.fromString(userId.toString()));
    }
}
