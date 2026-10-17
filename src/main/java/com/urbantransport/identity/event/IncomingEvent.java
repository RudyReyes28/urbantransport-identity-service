package com.urbantransport.identity.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record IncomingEvent(UUID eventId, String eventType, Map<String, Object> payload) {
}