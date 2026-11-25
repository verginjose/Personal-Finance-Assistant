package com.authservice.controller;

import com.authservice.dto.AuthDtos.*;
import com.authservice.exception.GlobalExceptionHandler;
import com.authservice.repository.UserRepository;
import com.authservice.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import static org.mockito.Mockito.*;

class AuthControllerTest {
    private final AuthService service = mock(AuthService.class);
    private final AuthController controller = new AuthController(service, mock(UserRepository.class));
    private final WebTestClient client = WebTestClient.bindToController(controller)
            .webFilter((exchange, chain) -> chain.filter(exchange)
                    .onErrorResume(error -> new GlobalExceptionHandler().handle(exchange, error))).build();

    @Test
    void missingRefreshTokenReturnsUnauthorized() {
        client.post().uri("/api/auth/refresh").exchange().expectStatus().isUnauthorized();
        client.post().uri("/api/auth/refresh").bodyValue(new RefreshTokenRequest(null))
                .exchange().expectStatus().isUnauthorized();
        client.post().uri("/api/auth/refresh").bodyValue(new RefreshTokenRequest(" "))
                .exchange().expectStatus().isUnauthorized();
        verifyNoInteractions(service);
    }

    @Test
    void cookieTakesPrecedenceOverBody() {
        when(service.refresh("cookie-token")).thenReturn(Mono.error(
                new AuthService.InvalidRefreshTokenException("Expired")));
        client.post().uri("/api/auth/refresh").cookie("pfa_refresh", "cookie-token")
                .bodyValue(new RefreshTokenRequest("body-token"))
                .exchange().expectStatus().isUnauthorized();
        verify(service).refresh("cookie-token");
    }

    @Test
    void bodyTokenStillWorks() {
        when(service.refresh("body-token")).thenReturn(Mono.error(
                new AuthService.InvalidRefreshTokenException("Expired")));
        client.post().uri("/api/auth/refresh").bodyValue(new RefreshTokenRequest("body-token"))
                .exchange().expectStatus().isUnauthorized();
        verify(service).refresh("body-token");
    }

    @Test
    void logoutWithoutRefreshTokenStillRevokesAccessToken() {
        when(service.logout("", "Bearer access")).thenReturn(Mono.empty());
        client.post().uri("/api/auth/logout").header("Authorization", "Bearer access")
                .exchange().expectStatus().isOk();
        verify(service).logout("", "Bearer access");
    }
}
