package com.authservice.service;

import com.authservice.dto.AuthDtos.UserSearchResult;
import com.authservice.repository.UserRepository;
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
    public List<UserSearchResult> searchUsers(String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        int capped = Math.min(Math.max(limit, 1), 20);
        return userRepository.searchByUsernameOrEmail(query.trim(), PageRequest.of(0, capped))
                .stream()
                .map(u -> new UserSearchResult(
                        u.getId().toString(),
                        u.getUsername(),
                        u.getEmail()))
                .toList();
    }
}
