package com.apigateway.auth.controller;

import com.apigateway.auth.dto.AuthDtos.UserSearchResult;
import com.apigateway.auth.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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
        return Mono.fromCallable(() -> ResponseEntity.ok(userService.searchUsers(q, limit, page)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/bulk")
    @PreAuthorize("isAuthenticated()")
    public Mono<ResponseEntity<List<UserSearchResult>>> getBulkUsers(
            @RequestBody Mono<List<UUID>> userIdsMono) {
        return userIdsMono.publishOn(Schedulers.boundedElastic())
                .map(userIds -> ResponseEntity.ok(userService.getBulkUsers(userIds)));
    }
}
