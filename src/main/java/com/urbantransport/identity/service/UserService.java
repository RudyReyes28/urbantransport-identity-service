package com.urbantransport.identity.service;


import com.urbantransport.identity.domain.Role;
import com.urbantransport.identity.dto.*;

import java.util.UUID;

public interface UserService {

    UserResponse create(CreateUserRequest request);

    PageResponse<UserResponse> search(Role role, UUID branchId, Boolean active, String query, int page, int size);

    UserResponse findById(UUID id);

    UserResponse update(UUID id, UpdateUserRequest request);

    UserResponse activate(UUID id);

    UserResponse deactivate(UUID id, UUID actorId);

    void resetPassword(UUID id, ResetPasswordRequest request);
}