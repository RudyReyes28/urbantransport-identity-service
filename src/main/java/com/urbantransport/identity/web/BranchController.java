package com.urbantransport.identity.web;

import com.urbantransport.identity.service.BranchService;
import com.urbantransport.identity.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/branches")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class BranchController {

    private final BranchService branchService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BranchCreatedResponse create(@Valid @RequestBody CreateBranchRequest request) {
        return branchService.create(request);
    }

    @GetMapping
    public List<BranchResponse> findAll(@RequestParam(required = false) Boolean active) {
        return branchService.findAll(active);
    }

    @GetMapping("/{id}")
    public BranchResponse findById(@PathVariable UUID id) {
        return branchService.findById(id);
    }

    @PutMapping("/{id}")
    public BranchResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateBranchRequest request) {
        return branchService.update(id, request);
    }

    @PatchMapping("/{id}/activate")
    public BranchResponse activate(@PathVariable UUID id) {
        return branchService.activate(id);
    }

    @PatchMapping("/{id}/deactivate")
    public BranchResponse deactivate(@PathVariable UUID id) {
        return branchService.deactivate(id);
    }
}