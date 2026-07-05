package com.finance.query.dto;

import lombok.*;

import java.io.Serializable;
import java.util.List;


@Data
@AllArgsConstructor
@NoArgsConstructor
public class ChartData implements Serializable {
    private static final long serialVersionUID = 1L;

    // Getters and Setters
    private String chartType; // "pie", "line", "bar"
    private String title;
    private List<String> labels;
    private List<DataSet> datasets;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DataSet implements Serializable {
        private static final long serialVersionUID = 1L;
        private String label;
        private List<Double> data;
        private List<String> backgroundColor;
        private List<String> borderColor;
        private int borderWidth = 1;

        public DataSet(String label, List<Double> data, List<String> backgroundColor) {
            this.label = label;
            this.data = data;
            this.backgroundColor = backgroundColor;
        }
    }
}

