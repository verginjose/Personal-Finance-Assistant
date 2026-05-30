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

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

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

                        // RBAC: route-level role restrictions
                        .pathMatchers("/api/upsert/**").hasAnyRole("USER", "ADMIN")
                        .pathMatchers("/api/bill/**").hasAnyRole("USER", "ADMIN")
                        .pathMatchers("/api/analytics/**").hasAnyRole("USER", "ADMIN")

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