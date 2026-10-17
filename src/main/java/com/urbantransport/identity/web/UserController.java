package com.urbantransport.identity.web;


import com.urbantransport.identity.domain.Role;
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
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class UserController {

    private final UserService userService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse create(@Valid @RequestBody CreateUserRequest request) {
        return userService.create(request);
    }

    @GetMapping
    public PageResponse<UserResponse> search(@RequestParam(required = false) Role role,
                                             @RequestParam(required = false) UUID branchId,
                                             @RequestParam(required = false) Boolean active,
                                             @RequestParam(name = "q", required = false) String query,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return userService.search(role, branchId, active, query, page, size);
    }

    @GetMapping("/{id}")
    public UserResponse findById(@PathVariable UUID id) {
        return userService.findById(id);
    }

    @PutMapping("/{id}")
    public UserResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateUserRequest request) {
        return userService.update(id, request);
    }

    @PatchMapping("/{id}/activate")
    public UserResponse activate(@PathVariable UUID id) {
        return userService.activate(id);
    }

    @PatchMapping("/{id}/deactivate")
    public UserResponse deactivate(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return userService.deactivate(id, UUID.fromString(jwt.getSubject()));
    }

    @PatchMapping("/{id}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@PathVariable UUID id, @Valid @RequestBody ResetPasswordRequest request) {
        userService.resetPassword(id, request);
    }
}