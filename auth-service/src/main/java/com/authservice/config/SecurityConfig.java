package com.authservice.config;

import com.authservice.filter.JwtAuthenticationFilter;
import com.authservice.security.UserDetailsServiceImpl;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security configuration.
 *
 * Key decisions:
 * - Stateless (JWT, no sessions)
 * - CSRF disabled (safe for stateless APIs)
 * - @EnableMethodSecurity allows @PreAuthorize on controllers/services
 * - DaoAuthenticationProvider wires UserDetailsService + PasswordEncoder together
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity                   // enables @PreAuthorize, @PostAuthorize
@RequiredArgsConstructor
public class SecurityConfig {

  private final JwtAuthenticationFilter jwtAuthFilter;
  private final UserDetailsServiceImpl  userDetailsService;

  // ── Security filter chain ─────────────────────────────────────────────────

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // ── URL-level RBAC rules ──────────────────────────────────────────
            .authorizeHttpRequests(auth -> auth

                    // Public: auth endpoints (login, register, health)
                    .requestMatchers(
                            "/auth/login",
                            "/auth/register",
                            "/auth/refresh",
                            "/auth/health",
                            "/actuator/**"
                    ).permitAll()

                    // Admin-only management endpoints
                    .requestMatchers("/auth/admin/**").hasRole("ADMIN")

                    // Moderator and Admin
                    .requestMatchers("/auth/moderation/**").hasAnyRole("MODERATOR", "ADMIN")

                    // Any authenticated user
                    .requestMatchers("/api/**").authenticated()

                    // Everything else requires authentication
                    .anyRequest().authenticated()
            )

            // ── Exception handling ────────────────────────────────────────────
            .exceptionHandling(ex -> ex
                    // 401: not authenticated
                    .authenticationEntryPoint((request, response, authException) -> {
                      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                      response.setContentType("application/json");
                      response.getWriter().write(
                              "{\"error\":\"Unauthorized\",\"message\":\"" + authException.getMessage() + "\"}"
                      );
                    })
                    // 403: authenticated but lacks role
                    .accessDeniedHandler((request, response, accessDeniedException) -> {
                      response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                      response.setContentType("application/json");
                      response.getWriter().write(
                              "{\"error\":\"Forbidden\",\"message\":\"You do not have permission to access this resource\"}"
                      );
                    })
            )

            // JWT filter runs BEFORE Spring's username/password filter
            .authenticationProvider(authenticationProvider())
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

    return http.build();
  }

  // ── Auth infrastructure beans ─────────────────────────────────────────────

  /**
   * DaoAuthenticationProvider:
   * - Loads user via UserDetailsService
   * - Verifies password via PasswordEncoder
   * This is what AuthenticationManager calls during login.
   */
  @Bean
  public AuthenticationProvider authenticationProvider() {
    var provider = new DaoAuthenticationProvider();
    provider.setUserDetailsService(userDetailsService);
    provider.setPasswordEncoder(passwordEncoder());
    return provider;
  }

  /**
   * Exposes AuthenticationManager as a bean so AuthService can call
   * authManager.authenticate(...) during login.
   */
  @Bean
  public AuthenticationManager authenticationManager(AuthenticationConfiguration config)
          throws Exception {
    return config.getAuthenticationManager();
  }

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);  // strength 12 — production default
  }
}