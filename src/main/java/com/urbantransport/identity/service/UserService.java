package com.urbantransport.identity.service;

import com.urbantransport.identity.domain.Role;
import com.urbantransport.identity.security.Actor;
import com.urbantransport.identity.dto.*;

import java.util.UUID;

public interface UserService {

    UserResponse create(Actor actor, CreateUserRequest request);

    PageResponse<UserResponse> search(Actor actor, Role role, UUID branchId, Boolean active, String query,
                                      int page, int size);

    UserResponse findById(Actor actor, UUID id);

    UserResponse update(Actor actor, UUID id, UpdateUserRequest request);

    UserResponse activate(Actor actor, UUID id);

    UserResponse deactivate(Actor actor, UUID id);

    void resetPassword(Actor actor, UUID id, ResetPasswordRequest request);
}