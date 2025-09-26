package com.upsertservice.dto;

import java.time.LocalDateTime;
import java.util.List;

public class ErrorResponse {
    private String message;
    private List<String> details;
    private LocalDateTime timestamp;
    private int status;

    public ErrorResponse(String message, List<String> details, int status) {
        this.message = message;
        this.details = details;
        this.status = status;
        this.timestamp = LocalDateTime.now();
    }

    // Getters and Setters
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public List<String> getDetails() { return details; }
    public void setDetails(List<String> details) { this.details = details; }

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }

    public int getStatus() { return status; }
    public void setStatus(int status) { this.status = status; }
}

