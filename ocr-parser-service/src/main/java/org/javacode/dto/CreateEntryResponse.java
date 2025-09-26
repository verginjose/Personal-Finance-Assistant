package org.javacode.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CreateEntryResponse {

    private String userId;
    private String name;

    private String amount;

    private String type;

    private String expenseCategory;

    private String incomeCategory;

    private String currency;
    private String description;
}
