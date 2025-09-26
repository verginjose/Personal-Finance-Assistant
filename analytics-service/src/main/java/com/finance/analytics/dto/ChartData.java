package com.finance.analytics.dto;

import java.util.List;

public class ChartData {
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

    // Getters and Setters
    public String getChartType() { return chartType; }
    public void setChartType(String chartType) { this.chartType = chartType; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public List<String> getLabels() { return labels; }
    public void setLabels(List<String> labels) { this.labels = labels; }

    public List<DataSet> getDatasets() { return datasets; }
    public void setDatasets(List<DataSet> datasets) { this.datasets = datasets; }

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

