package com.urbantransport.identity.web;

import com.urbantransport.identity.service.InternalUserService;
import com.urbantransport.identity.dto.CreateCustomerUserRequest;
import com.urbantransport.identity.dto.CreateDriverUserRequest;
import com.urbantransport.identity.dto.UserResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('INTERNAL_SERVICE')")
public class InternalUserController {

    private final InternalUserService internalUserService;

    @PostMapping("/customer")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse createCustomerUser(@Valid @RequestBody CreateCustomerUserRequest request) {
        return internalUserService.createCustomerUser(request);
    }

    @PostMapping("/driver")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse createDriverUser(@Valid @RequestBody CreateDriverUserRequest request) {
        return internalUserService.createDriverUser(request);
    }
}