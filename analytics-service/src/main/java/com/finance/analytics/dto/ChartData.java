package com.finance.analytics.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Setter
@Getter
public class ChartData {
    // Getters and Setters
    private String chartType; // "pie", "line", "bar"
    private String title;
    private List<String> labels;
    private List<DataSet> datasets;

    // Constructors
    public ChartData() {}

    public ChartData(String chartType, String title, List<String> labels, List<DataSet> datasets) {
        this.chartType = chartType;
        this.title = title;
        this.labels = labels;
        this.datasets = datasets;
    }

    public static class DataSet {
        private String label;
        private List<Double> data;
        private List<String> backgroundColor;
        private List<String> borderColor;
        private int borderWidth = 1;

        // Constructors
        public DataSet() {}

        public DataSet(String label, List<Double> data, List<String> backgroundColor) {
            this.label = label;
            this.data = data;
            this.backgroundColor = backgroundColor;
        }

        // Getters and Setters
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }

        public List<Double> getData() { return data; }
        public void setData(List<Double> data) { this.data = data; }

        public List<String> getBackgroundColor() { return backgroundColor; }
        public void setBackgroundColor(List<String> backgroundColor) { this.backgroundColor = backgroundColor; }

        public List<String> getBorderColor() { return borderColor; }
        public void setBorderColor(List<String> borderColor) { this.borderColor = borderColor; }

        public int getBorderWidth() { return borderWidth; }
        public void setBorderWidth(int borderWidth) { this.borderWidth = borderWidth; }
    }
}

