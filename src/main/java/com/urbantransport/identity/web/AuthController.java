package com.urbantransport.identity.web;

import com.urbantransport.identity.service.AuthService;
import com.urbantransport.identity.service.PasswordSetupService;
import com.urbantransport.identity.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final PasswordSetupService passwordSetupService;

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request);
    }

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return authService.me(UUID.fromString(jwt.getSubject()));
    }

    @PostMapping("/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(UUID.fromString(jwt.getSubject()), request);
    }

    @PostMapping("/password-setup")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setupPassword(@Valid @RequestBody SetupPasswordRequest request) {
        passwordSetupService.setup(request);
    }

    @PostMapping("/password-setup/resend")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resendPasswordSetup(@Valid @RequestBody ResendPasswordSetupRequest request) {
        passwordSetupService.resend(request);
    }
}