package com.urbantransport.identity.service;

import com.urbantransport.identity.dto.*;

import java.util.UUID;

public interface AuthService {

    TokenResponse login(LoginRequest request);

    TokenResponse refresh(RefreshRequest request);

    void logout(RefreshRequest request);

    UserResponse me(UUID userId);

    void changePassword(UUID userId, ChangePasswordRequest request);
}