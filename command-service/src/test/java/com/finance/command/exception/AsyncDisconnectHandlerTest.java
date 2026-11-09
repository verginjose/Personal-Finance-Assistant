package com.finance.command.exception;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AsyncDisconnectHandlerTest {
    @RestController
    static class DisconnectedStream {
        @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        String stream(jakarta.servlet.http.HttpServletResponse response)
                throws AsyncRequestNotUsableException {
            response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
            throw new AsyncRequestNotUsableException("Servlet container error notification for disconnected client");
        }
    }

    @Test
    void disconnectedStreamDoesNotWriteJsonOrCountServerError() throws Exception {
        var registry = new SimpleMeterRegistry();
        var mvc = MockMvcBuilders.standaloneSetup(new DisconnectedStream())
                .setControllerAdvice(new GlobalExceptionHandler(registry)).build();
        mvc.perform(get("/stream").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
        assertNull(registry.find("http.errors.total").counter());
    }
}
