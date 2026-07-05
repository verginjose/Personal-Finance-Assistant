package com.finance.query.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateGroupRequest {
    @NotBlank
    @Size(max = 100)
    private String name;

    @Size(max = 300)
    private String description;
    
    @Size(min = 3, max = 3)
    private String currency;
}
