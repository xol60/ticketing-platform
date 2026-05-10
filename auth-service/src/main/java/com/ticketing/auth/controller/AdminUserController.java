package com.ticketing.auth.controller;

import com.ticketing.auth.domain.model.User;
import com.ticketing.auth.domain.repository.UserRepository;
import com.ticketing.auth.dto.response.UserResponse;
import com.ticketing.common.dto.ApiResponse;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final UserRepository userRepository;

    @GetMapping
    public ApiResponse<Page<UserResponse>> listUsers(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {

        Page<UserResponse> result = userRepository
                .findAll(PageRequest.of(page, size, Sort.by("createdAt").descending()))
                .map(this::toResponse);
        return ApiResponse.ok(result, traceId);
    }

    @GetMapping("/{userId}")
    public ApiResponse<UserResponse> getUser(
            @PathVariable String userId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found: " + userId));
        return ApiResponse.ok(toResponse(user), traceId);
    }

    private UserResponse toResponse(User u) {
        return UserResponse.builder()
                .id(u.getId())
                .email(u.getEmail())
                .username(u.getUsername())
                .role(u.getRole().name())
                .tenantId(u.getTenantId())
                .enabled(u.isEnabled())
                .emailVerified(u.isEmailVerified())
                .createdAt(u.getCreatedAt())
                .build();
    }
}
