package org.javacode.dto;


import java.util.List;

public class BillProcessingResult {
    private String extractedText;
    private List<String> allAmounts;
    private String primaryAmount;

    public BillProcessingResult(String extractedText, List<String> allAmounts, String primaryAmount) {
        this.extractedText = extractedText;
        this.allAmounts = allAmounts;
        this.primaryAmount = primaryAmount;
    }

    // Getters and setters
    public String getExtractedText() { return extractedText; }
    public void setExtractedText(String extractedText) { this.extractedText = extractedText; }

    public List<String> getAllAmounts() { return allAmounts; }
    public void setAllAmounts(List<String> allAmounts) { this.allAmounts = allAmounts; }

    public String getPrimaryAmount() { return primaryAmount; }
    public void setPrimaryAmount(String primaryAmount) { this.primaryAmount = primaryAmount; }
}