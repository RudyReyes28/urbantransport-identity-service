package com.urbantransport.identity.event;

import com.urbantransport.identity.service.CustomerSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class CustomerEventsListener {

    private final JsonMapper jsonMapper;
    private final CustomerSyncService customerSyncService;

    @KafkaListener(topics = "${identity.kafka.customer-events-topic}")
    public void onCustomerEvent(String message) {
        IncomingEvent event = jsonMapper.readValue(message, IncomingEvent.class);
        if (!EventTypes.CUSTOMER_NIT_CHANGED.equals(event.eventType())) {
            log.debug("Evento {} ignorado", event.eventType());
            return;
        }
        UUID customerId = UUID.fromString(String.valueOf(event.payload().get("customerId")));
        String nit = String.valueOf(event.payload().get("nit"));
        customerSyncService.applyNitChange(event.eventId(), customerId, nit);
    }
}