package com.apigateway.auth.controller;

import com.apigateway.auth.dto.AuthDtos.UserSearchResult;
import com.apigateway.auth.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/search")
    @PreAuthorize("isAuthenticated()")
    public Mono<ResponseEntity<List<UserSearchResult>>> searchUsers(
            @RequestParam String q,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "0") int page) {
        return userService.searchUsers(q, limit, page).collectList()
                .map(ResponseEntity::ok);
    }

    @PostMapping("/bulk")
    @PreAuthorize("isAuthenticated()")
    public Mono<ResponseEntity<List<UserSearchResult>>> getBulkUsers(
            @RequestBody Mono<List<UUID>> userIdsMono) {
        return userIdsMono.flatMap(userIds -> userService.getBulkUsers(userIds).collectList())
                .map(ResponseEntity::ok);
    }
}
