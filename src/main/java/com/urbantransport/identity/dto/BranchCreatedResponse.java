package com.urbantransport.identity.dto;

import lombok.Value;

@Value
public class BranchCreatedResponse {
    BranchResponse branch;
    UserResponse admin;
}