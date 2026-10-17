package com.urbantransport.identity.service;

import java.util.UUID;

public interface CustomerSyncService {

    /** Aplica el cambio de NIT una sola vez por eventId. */
    void applyNitChange(UUID eventId, UUID customerId, String nit);
}