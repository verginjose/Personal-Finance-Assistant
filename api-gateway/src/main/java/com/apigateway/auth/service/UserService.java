package com.apigateway.auth.service;

import com.apigateway.auth.dto.AuthDtos.UserSearchResult;
import com.apigateway.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public Flux<UserSearchResult> searchUsers(String query, int limit, int page) {
        if (query == null || query.isBlank()) {
            return Flux.empty();
        }
        int capped = Math.clamp(limit, 1, 20);
        int safePage = Math.max(0, page);
        long offset = (long) safePage * capped;
        
        return userRepository.searchByUsernameOrEmail(query.trim(), capped, offset)
                .map(u -> new UserSearchResult(
                        u.getId().toString(),
                        u.getActualUsername(),
                        u.getEmail(),
                        u.getProfilePicture()));
    }

    @Transactional(readOnly = true)
    public Flux<UserSearchResult> getBulkUsers(List<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) return Flux.empty();
        return userRepository.findAllById(userIds)
                .map(u -> new UserSearchResult(
                        u.getId().toString(),
                        u.getActualUsername(),
                        u.getEmail(),
                        u.getProfilePicture()));
    }
}
