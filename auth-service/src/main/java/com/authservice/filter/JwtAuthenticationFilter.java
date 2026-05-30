package com.authservice.filter;

import com.authservice.security.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Intercepts every request once, validates the JWT, and sets the Authentication
 * object in the SecurityContext so Spring Security knows who this user is and
 * what roles they have — without hitting the DB again.
 *
 * We read the role directly from the token (already validated), so no DB call
 * is needed per request. The UserDetailsService is only used at login time.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        final String authHeader = request.getHeader("Authorization");

        // No token? Pass through — Spring Security will reject if endpoint is protected
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        final String token = authHeader.substring(7);

        // Already authenticated in this request? Skip
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!jwtService.isTokenValid(token)) {
            log.warn("Invalid JWT token for request: {} {}", request.getMethod(), request.getRequestURI());
            filterChain.doFilter(request, response);
            return;
        }

        String email  = jwtService.extractEmail(token);
        String role   = jwtService.extractRole(token);   // e.g. "ADMIN"
        String userId = jwtService.extractUserId(token);

        // Build GrantedAuthority from the token's role claim
        // Spring Security expects "ROLE_ADMIN" format for hasRole() checks
        var authority = new SimpleGrantedAuthority("ROLE_" + role);

        var authToken = new UsernamePasswordAuthenticationToken(
                email,          // principal (no need to load full UserDetails from DB)
                null,           // credentials (null after authentication)
                List.of(authority)
        );
        authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

        // Store userId in request attribute so controllers can access it
        request.setAttribute("userId", userId);

        SecurityContextHolder.getContext().setAuthentication(authToken);
        log.debug("Authenticated user: {} with role: {}", email, role);

        filterChain.doFilter(request, response);
    }
}