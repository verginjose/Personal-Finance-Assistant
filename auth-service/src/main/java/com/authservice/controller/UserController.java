package com.authservice.controller;

import com.authservice.dto.AuthDtos.UserSearchResult;
import com.authservice.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
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
            Authentication authentication,
            @RequestParam String q,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "0") int page) {
        return userService.searchUsers(q, limit, page, authentication.getName()).collectList()
                .map(ResponseEntity::ok);
    }

    /** Profile of another user (visible to any signed-in user, e.g. group peers). */
    @GetMapping("/{userId}")
    @PreAuthorize("isAuthenticated()")
    public Mono<ResponseEntity<UserSearchResult>> getUser(
            Authentication authentication,
            @PathVariable UUID userId) {
        return userService.getUser(userId, authentication.getName())
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @PostMapping("/bulk")
    @PreAuthorize("isAuthenticated()")
    public Mono<ResponseEntity<List<UserSearchResult>>> getBulkUsers(
            Authentication authentication,
            @RequestBody Mono<List<UUID>> userIdsMono) {
        return userIdsMono.flatMap(userIds ->
                        userService.getBulkUsers(userIds, authentication.getName()).collectList())
                .map(ResponseEntity::ok);
    }
}
