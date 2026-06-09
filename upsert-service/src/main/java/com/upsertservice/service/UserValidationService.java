package com.upsertservice.service;

import com.upsertservice.model.RegisteredUser;
import com.upsertservice.repository.RegisteredUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserValidationService {

    private final RegisteredUserRepository registeredUserRepository;

    @Transactional(readOnly = true)
    public RegisteredUser requireRegisteredUser(UUID userId) {
        return registeredUserRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "User not found. Only registered users can be added to groups."));
    }

    public String displayName(RegisteredUser user) {
        return user.getUsername() != null ? user.getUsername() : user.getEmail();
    }
}
