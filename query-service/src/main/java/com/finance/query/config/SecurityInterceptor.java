package com.finance.query.config;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;

@Component
public class SecurityInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String uri = request.getRequestURI();
        // Allow public/health/actuator/internal endpoints without validation
        if (uri.contains("/health") || uri.contains("/actuator") || uri.contains("/error") || uri.startsWith("/internal/")) {
            return true;
        }

        String xUserId = request.getHeader("X-User-Id");
        if (xUserId == null || xUserId.isBlank()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write("{\"message\":\"Unauthorized: Missing user context\"}");
            return false;
        }

        // 1. Validate Query Parameter "userId"
        String queryUserId = request.getParameter("userId");
        if (queryUserId != null && !queryUserId.equalsIgnoreCase(xUserId)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.getWriter().write("{\"message\":\"Forbidden: Access denied for requested user ID\"}");
            return false;
        }

        // 2. Validate Path Variables (e.g. /{userId} or similar)
        Map<?, ?> pathVariables = (Map<?, ?>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (pathVariables != null) {
            Object pathUserId = pathVariables.get("userId");
            if (pathUserId != null && !pathUserId.toString().equalsIgnoreCase(xUserId)) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.getWriter().write("{\"message\":\"Forbidden: Access denied for requested user ID\"}");
                return false;
            }
        }

        return true;
    }
}
