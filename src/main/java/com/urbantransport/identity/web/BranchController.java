package com.urbantransport.identity.web;

import com.urbantransport.identity.service.BranchService;
import com.urbantransport.identity.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/branches")
@RequiredArgsConstructor
public class BranchController {

    private static final String ROLE_SYSTEM_ADMIN = "ROLE_SYSTEM_ADMIN";

    private final BranchService branchService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public BranchCreatedResponse create(@Valid @RequestBody CreateBranchRequest request) {
        return branchService.create(request);
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<BranchResponse> findAll(@RequestParam(required = false) Boolean active, Authentication authentication) {
        return branchService.findAll(isSystemAdmin(authentication) ? active : Boolean.TRUE);
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public BranchResponse findById(@PathVariable UUID id) {
        return branchService.findById(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public BranchResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateBranchRequest request) {
        return branchService.update(id, request);
    }

    @PatchMapping("/{id}/activate")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public BranchResponse activate(@PathVariable UUID id) {
        return branchService.activate(id);
    }

    @PatchMapping("/{id}/deactivate")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public BranchResponse deactivate(@PathVariable UUID id) {
        return branchService.deactivate(id);
    }

    private boolean isSystemAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> ROLE_SYSTEM_ADMIN.equals(authority.getAuthority()));
    }
}
