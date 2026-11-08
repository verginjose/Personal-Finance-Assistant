package com.authservice.service;

import com.authservice.dto.AuthDtos.UserSearchResult;
import com.authservice.model.User;
import com.authservice.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    /**
     * A profile result. The email is personal data: it is returned only when
     * the caller is that same account. Group peers get the username and
     * picture they actually need, never the address.
     */
    private static UserSearchResult toResult(User u, String requesterEmail) {
        boolean self = requesterEmail != null
                && requesterEmail.equalsIgnoreCase(u.getEmail());
        return new UserSearchResult(
                u.getId().toString(),
                u.getActualUsername(),
                self ? u.getEmail() : null,
                u.getProfilePicture());
    }

    @Transactional(readOnly = true)
    public Flux<UserSearchResult> searchUsers(String query, int limit, int page, String requesterEmail) {
        if (query == null || query.isBlank()) {
            return Flux.empty();
        }
        int capped = Math.clamp(limit, 1, 20);
        int safePage = Math.max(0, page);
        long offset = (long) safePage * capped;

        return userRepository.searchByUsernameOrEmail(query.trim(), capped, offset)
                .map(u -> toResult(u, requesterEmail));
    }

    /**
     * Public profile of another registered user. Group members use this to see
     * each other; it exposes only username, picture and (for the caller) email.
     */
    @Transactional(readOnly = true)
    public Mono<UserSearchResult> getUser(UUID userId, String requesterEmail) {
        return userRepository.findById(userId)
                .map(u -> toResult(u, requesterEmail));
    }

    @Transactional(readOnly = true)
    public Flux<UserSearchResult> getBulkUsers(List<UUID> userIds, String requesterEmail) {
        if (userIds == null || userIds.isEmpty()) return Flux.empty();
        return userRepository.findAllById(userIds)
                .map(u -> toResult(u, requesterEmail));
    }
}
