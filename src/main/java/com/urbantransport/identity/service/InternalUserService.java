package com.urbantransport.identity.service;

import com.urbantransport.identity.dto.*;

public interface InternalUserService {

    UserResponse createCustomerUser(CreateCustomerUserRequest request);
}