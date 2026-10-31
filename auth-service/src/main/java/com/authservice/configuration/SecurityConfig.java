package com.authservice.configuration;

import com.authservice.security.JwtAuthenticationConverter;
import com.authservice.security.JwtReactiveAuthenticationManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;
import org.springframework.security.web.server.authentication.ServerAuthenticationEntryPointFailureHandler;
import org.springframework.security.web.server.authorization.HttpStatusServerAccessDeniedHandler;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;

@Configuration
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationConverter jwtAuthenticationConverter;
    private final JwtReactiveAuthenticationManager authenticationManager;

    public SecurityConfig(JwtAuthenticationConverter jwtAuthenticationConverter,
                          JwtReactiveAuthenticationManager authenticationManager) {
        this.jwtAuthenticationConverter = jwtAuthenticationConverter;
        this.authenticationManager = authenticationManager;
    }

    /** BCrypt is CPU-bound; AuthService runs it on the boundedElastic pool. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {

        AuthenticationWebFilter jwtFilter = new AuthenticationWebFilter(authenticationManager);
        jwtFilter.setServerAuthenticationConverter(jwtAuthenticationConverter);
        jwtFilter.setSecurityContextRepository(NoOpServerSecurityContextRepository.getInstance());
        jwtFilter.setAuthenticationFailureHandler(
                new ServerAuthenticationEntryPointFailureHandler(
                        new HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED)));

        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(corsSpec -> {})
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/actuator/**").permitAll()
                        // Public credential endpoints
                        .pathMatchers("/api/auth/login",
                                      "/api/auth/register",
                                      "/api/auth/refresh",
                                      "/api/auth/health").permitAll()
                        // /me, /profile, /logout, /change-password, /users/** need a token
                        .anyExchange().authenticated()
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler(new HttpStatusServerAccessDeniedHandler(HttpStatus.FORBIDDEN))
                )
                .addFilterAt(jwtFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }
}
