package com.authservice.service;

import com.authservice.dto.AuthDtos.*;
import com.authservice.model.Role;
import com.authservice.model.User;
import com.authservice.repository.UserRepository;
import com.authservice.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService — Unit Tests")
public class AuthServiceTest {

    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JwtService jwtService;
    @Mock AuthenticationManager authenticationManager;
    @Mock TokenRedisService tokenRedisService;

    @InjectMocks AuthService authService;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .id(UUID.randomUUID())
                .email("test@example.com")
                .username("testuser")
                .password("encoded_password")
                .role(Role.USER)
                .build();
    }

    @Test
    @DisplayName("register: throws exception if email already exists")
    void register_emailExists_throwsException() {
        RegisterRequest request = new RegisterRequest("test@example.com", "testuser", "password", Role.USER);
        when(userRepository.existsByEmail("test@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(AuthService.EmailAlreadyExistsException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("register: saves new user when email is unique")
    void register_uniqueEmail_savesUser() {
        RegisterRequest request = new RegisterRequest("new@example.com", "newuser", "password", Role.USER);
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(passwordEncoder.encode("password")).thenReturn("encoded_pass");
        when(userRepository.save(any(User.class))).thenAnswer(i -> {
            User u = i.getArgument(0);
            u.setId(UUID.randomUUID());
            return u;
        });

        RegisterResponse response = authService.register(request);

        assertThat(response.email()).isEqualTo("new@example.com");
        assertThat(response.role()).isEqualTo("USER");
        verify(userRepository).save(any(User.class));
    }

    @Test
    @DisplayName("login: succeeds with correct credentials")
    void login_validCredentials_returnsLoginResponse() {
        LoginRequest request = new LoginRequest("test@example.com", "password");
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(jwtService.generateToken(user)).thenReturn("jwt_token");

        LoginResponse response = authService.login(request);

        assertThat(response.token()).isEqualTo("jwt_token");
        assertThat(response.email()).isEqualTo("test@example.com");
        verify(authenticationManager).authenticate(any());
        verify(tokenRedisService).saveRefreshToken(anyString(), eq("test@example.com"), anyString(), eq("USER"), anyLong());
    }

    @Test
    @DisplayName("logout: deletes session if token exists")
    void logout_validToken_deletesSession() {
        when(tokenRedisService.getRefreshTokenValue("token123"))
                .thenReturn("user_uuid|test@example.com|USER");

        authService.logout("token123");

        verify(tokenRedisService).deleteRefreshToken("token123", "test@example.com");
    }

    @Test
    @DisplayName("changePassword: saves encoded new password when current matches")
    void changePassword_validRequest_updatesPassword() {
        ChangePasswordRequest request = new ChangePasswordRequest("password", "new_password");
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password", "encoded_password")).thenReturn(true);
        when(passwordEncoder.encode("new_password")).thenReturn("new_encoded_password");

        authService.changePassword("test@example.com", request);

        assertThat(user.getPassword()).isEqualTo("new_encoded_password");
        verify(userRepository).save(user);
        verify(tokenRedisService).revokeAllUserTokens("test@example.com");
    }

    @Test
    @DisplayName("changePassword: throws exception when current password mismatches")
    void changePassword_mismatch_throwsException() {
        ChangePasswordRequest request = new ChangePasswordRequest("wrong", "new_password");
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "encoded_password")).thenReturn(false);

        assertThatThrownBy(() -> authService.changePassword("test@example.com", request))
                .isInstanceOf(AuthService.InvalidCurrentPasswordException.class);

        verify(userRepository, never()).save(any());
    }
}
