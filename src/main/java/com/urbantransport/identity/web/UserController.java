package com.urbantransport.identity.web;

import com.urbantransport.identity.domain.Role;
import com.urbantransport.identity.security.Actor;
import com.urbantransport.identity.service.UserService;
import com.urbantransport.identity.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'BRANCH_ADMIN')")
public class UserController {

    private final UserService userService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateUserRequest request) {
        return userService.create(Actor.from(jwt), request);
    }

    @GetMapping
    public PageResponse<UserResponse> search(@AuthenticationPrincipal Jwt jwt,
                                             @RequestParam(required = false) Role role,
                                             @RequestParam(required = false) UUID branchId,
                                             @RequestParam(required = false) Boolean active,
                                             @RequestParam(name = "q", required = false) String query,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return userService.search(Actor.from(jwt), role, branchId, active, query, page, size);
    }

    @GetMapping("/{id}")
    public UserResponse findById(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return userService.findById(Actor.from(jwt), id);
    }

    @PutMapping("/{id}")
    public UserResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                               @Valid @RequestBody UpdateUserRequest request) {
        return userService.update(Actor.from(jwt), id, request);
    }

    @PatchMapping("/{id}/activate")
    public UserResponse activate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return userService.activate(Actor.from(jwt), id);
    }

    @PatchMapping("/{id}/deactivate")
    public UserResponse deactivate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return userService.deactivate(Actor.from(jwt), id);
    }

    @PatchMapping("/{id}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                              @Valid @RequestBody ResetPasswordRequest request) {
        userService.resetPassword(Actor.from(jwt), id, request);
    }
}