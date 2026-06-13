package com.authservice.controller;

import com.authservice.dto.AuthDtos.UserSearchResult;
import com.authservice.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/auth/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/search")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<UserSearchResult>> searchUsers(
            @RequestParam String q,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "0") int page) {
        return ResponseEntity.ok(userService.searchUsers(q, limit, page));
    }

    @org.springframework.web.bind.annotation.PostMapping("/bulk")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<UserSearchResult>> getBulkUsers(
            @org.springframework.web.bind.annotation.RequestBody List<java.util.UUID> userIds) {
        return ResponseEntity.ok(userService.getBulkUsers(userIds));
    }
}
