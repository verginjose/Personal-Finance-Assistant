package org.javacode.service;


import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class OcrResponse {
    @JsonProperty("full_text")
    private String fullText;
    private List<Map<String, Object>> lines;
    @JsonProperty("inference_ms")
    private double inferenceMs;
}