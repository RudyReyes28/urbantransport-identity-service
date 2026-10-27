package com.urbantransport.identity.service;

import java.util.UUID;

public interface DriverSyncService {

    void applyDriverDeactivated(UUID eventId, UUID userId);
}
