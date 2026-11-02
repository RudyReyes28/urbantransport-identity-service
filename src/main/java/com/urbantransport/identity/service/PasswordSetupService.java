package com.urbantransport.identity.service;

import com.urbantransport.identity.domain.UserEntity;
import com.urbantransport.identity.dto.ResendPasswordSetupRequest;
import com.urbantransport.identity.dto.SetupPasswordRequest;

public interface PasswordSetupService {

    String unusablePasswordHash();

    void issue(UserEntity user);

    void resend(ResendPasswordSetupRequest request);

    void setup(SetupPasswordRequest request);
}
