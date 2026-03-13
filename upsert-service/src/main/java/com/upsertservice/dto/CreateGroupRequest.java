package com.upsertservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

@Data
public class CreateGroupRequest {
    @NotBlank @Size(max = 100)
    private String name;
    @Size(max = 300)
    private String description;
    @NotNull
    private UUID createdBy;
    private String currency = "INR";
}
