package com.urbantransport.identity.event;

import com.urbantransport.identity.domain.Role;
import lombok.Value;

import java.time.Instant;
import java.util.UUID;

@Value
public class PasswordSetupRequestedPayload {
    UUID userId;
    String email;
    String fullName;
    Role role;
    PasswordSetupPurpose purpose;
    String setupUrl;
    Instant expiresAt;
}
