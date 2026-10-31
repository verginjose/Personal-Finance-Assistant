package com.authservice.exception;

import com.authservice.service.AuthService;
import lombok.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
@Order(-1)
public class GlobalExceptionHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Override
    public Mono<Void> handle(@NonNull ServerWebExchange exchange, @NonNull Throwable ex) {
        if (ex instanceof org.springframework.security.core.AuthenticationException
                || ex instanceof AuthService.InvalidRefreshTokenException) {
            return respond(exchange, HttpStatus.UNAUTHORIZED, "Unauthorized", "Invalid or expired token");
        }

        if (ex instanceof org.springframework.security.access.AccessDeniedException) {
            return respond(exchange, HttpStatus.FORBIDDEN, "Forbidden", "Access Denied");
        }

        if (ex instanceof WebExchangeBindException bind) {
            String msg = bind.getFieldErrors().isEmpty()
                    ? "Invalid request body"
                    : bind.getFieldErrors().get(0).getField() + " " + bind.getFieldErrors().get(0).getDefaultMessage();
            return respond(exchange, HttpStatus.BAD_REQUEST, "Bad Request", msg);
        }

        if (ex instanceof AuthService.EmailAlreadyExistsException
                || ex instanceof AuthService.UsernameAlreadyExistsException
                || ex instanceof AuthService.InvalidCurrentPasswordException
                || ex instanceof IllegalArgumentException) {
            return respond(exchange, HttpStatus.BAD_REQUEST, "Bad Request", ex.getMessage());
        }

        log.error("auth-service error: {} {}", exchange.getRequest().getMethod(),
                exchange.getRequest().getURI(), ex);
        return respond(exchange, HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                "An unexpected server error occurred");
    }

    private Mono<Void> respond(ServerWebExchange exchange, HttpStatus status, String error, String message) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = String.format("{\"error\":\"%s\",\"message\":\"%s\"}",
                error, message == null ? "" : message.replace("\\", "\\\\").replace("\"", "'"));
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body.getBytes());
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
