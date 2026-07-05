package com.apigateway.configuration;
import com.apigateway.security.JwtAuthenticationConverter;
import com.apigateway.security.JwtReactiveAuthenticationManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;
import org.springframework.security.web.server.authorization.HttpStatusServerAccessDeniedHandler;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.authentication.ServerAuthenticationEntryPointFailureHandler;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private final JwtAuthenticationConverter jwtAuthenticationConverter;
    private final JwtReactiveAuthenticationManager authenticationManager;

    public SecurityConfig(JwtAuthenticationConverter jwtAuthenticationConverter,
                          JwtReactiveAuthenticationManager authenticationManager) {
        this.jwtAuthenticationConverter = jwtAuthenticationConverter;
        this.authenticationManager = authenticationManager;
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {

        AuthenticationWebFilter jwtFilter = new AuthenticationWebFilter(authenticationManager);
        jwtFilter.setServerAuthenticationConverter(jwtAuthenticationConverter);
        // Stateless — no session
        jwtFilter.setSecurityContextRepository(NoOpServerSecurityContextRepository.getInstance());
        // Prevent default WWW-Authenticate: Basic header on auth failure
        jwtFilter.setAuthenticationFailureHandler(new ServerAuthenticationEntryPointFailureHandler(new HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED)));

        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                // CORS is handled by your existing CorsConfig bean — Spring Security respects it
                .cors(corsSpec -> {})

                // Stateless JWT — no sessions
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())

                .authorizeExchange(exchanges -> exchanges
                        // Prometheus scraping — must be public
                        .pathMatchers("/actuator/**").permitAll()

                        // Public: auth endpoints (login, register, etc.)
                        .pathMatchers("/api/auth/**").permitAll()
                        .pathMatchers("/api/upsert/health").permitAll()
                        .pathMatchers("/api/analytics/health").permitAll()
                        .pathMatchers("/api/bill/health").permitAll()


                        // Everything else requires authentication
                        .anyExchange().authenticated()
                )

                // Return 401 (not redirect to login page)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler(new HttpStatusServerAccessDeniedHandler(HttpStatus.FORBIDDEN))
                )

                // Register our JWT filter before Spring's default auth processing
                .addFilterAt(jwtFilter, SecurityWebFiltersOrder.AUTHENTICATION)

                .build();
    }
}