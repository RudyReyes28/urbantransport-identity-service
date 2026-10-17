package com.urbantransport.identity.service;


import com.urbantransport.identity.dto.*;

import java.util.List;
import java.util.UUID;

public interface BranchService {

    BranchCreatedResponse create(CreateBranchRequest request);

    List<BranchResponse> findAll(Boolean active);

    BranchResponse findById(UUID id);

    BranchResponse update(UUID id, UpdateBranchRequest request);

    BranchResponse activate(UUID id);

    BranchResponse deactivate(UUID id);
}