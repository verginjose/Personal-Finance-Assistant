package com.apigateway.auth.service;

import com.apigateway.auth.dto.AuthDtos.UserSearchResult;
import com.apigateway.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<UserSearchResult> searchUsers(String query, int limit, int page) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        int capped = Math.clamp(limit, 1, 20);
        int safePage = Math.max(0, page);
        return userRepository.searchByUsernameOrEmail(query.trim(), PageRequest.of(safePage, capped))
                .stream()
                .map(u -> new UserSearchResult(
                        u.getId().toString(),
                        u.getActualUsername(),
                        u.getEmail(),
                        u.getProfilePicture()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<UserSearchResult> getBulkUsers(List<java.util.UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) return List.of();
        return userRepository.findAllById(userIds).stream()
                .map(u -> new UserSearchResult(
                        u.getId().toString(),
                        u.getActualUsername(),
                        u.getEmail(),
                        u.getProfilePicture()))
                .toList();
    }
}
