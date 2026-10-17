package com.urbantransport.identity.service.impl;

import com.urbantransport.identity.domain.Role;
import com.urbantransport.identity.domain.UserEntity;
import com.urbantransport.identity.event.EventTypes;
import com.urbantransport.identity.event.UserEventPayload;
import com.urbantransport.identity.exception.ApiException;
import com.urbantransport.identity.repository.UserRepository;
import com.urbantransport.identity.service.InternalUserService;
import com.urbantransport.identity.service.OutboxService;
import com.urbantransport.identity.dto.CreateCustomerUserRequest;
import com.urbantransport.identity.dto.UserResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
@RequiredArgsConstructor
public class InternalUserServiceImpl implements InternalUserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final OutboxService outboxService;

    @Override
    @Transactional
    public UserResponse createCustomerUser(CreateCustomerUserRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new ApiException(HttpStatus.CONFLICT, "Ya existe un usuario con el correo " + email);
        }
        if (userRepository.existsByCustomerId(request.customerId())) {
            throw new ApiException(HttpStatus.CONFLICT, "El cliente ya tiene una cuenta");
        }

        UserEntity user = userRepository.save(UserEntity.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(request.password()))
                .fullName(request.fullName().trim())
                .role(Role.CUSTOMER)
                .customerId(request.customerId())
                .nit(request.nit().trim().toUpperCase(Locale.ROOT))
                .build());

        outboxService.record(EventTypes.AGGREGATE_USER, user.getId(), EventTypes.USER_CREATED,
                UserEventPayload.fromEntity(user));
        return UserResponse.fromEntity(user);
    }
}