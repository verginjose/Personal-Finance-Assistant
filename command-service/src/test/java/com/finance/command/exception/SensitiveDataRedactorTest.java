package com.finance.command.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SensitiveDataRedactor — masks ids before response")
class SensitiveDataRedactorTest {

    @Test
    @DisplayName("redacts numeric entity ids but keeps the message readable")
    void redactsEntityIds() {
        assertThat(SensitiveDataRedactor.redact("Transaction entry with ID 156172 not found"))
                .isEqualTo("Transaction entry with ID <id> not found");
        assertThat(SensitiveDataRedactor.redact("Expense 178 not found"))
                .isEqualTo("Expense <id> not found");
        assertThat(SensitiveDataRedactor.redact("Group 388 not found"))
                .isEqualTo("Group <id> not found");
        assertThat(SensitiveDataRedactor.redact("Budget not found: 5"))
                .isEqualTo("Budget not found: <id>");
    }

    @Test
    @DisplayName("redacts uuids and emails")
    void redactsUuidsAndEmails() {
        assertThat(SensitiveDataRedactor.redact(
                "user 73252420-7a01-41ad-9b2b-c8d092d44fbc not found"))
                .isEqualTo("user <id> not found");
        assertThat(SensitiveDataRedactor.redact("rebuff@gmail.com already exists"))
                .isEqualTo("<email> already exists");
    }

    @Test
    @DisplayName("leaves safe domain messages untouched")
    void keepsSafeMessages() {
        String safe = "Cannot delete an expense with recorded settlements";
        assertThat(SensitiveDataRedactor.redact(safe)).isEqualTo(safe);
        assertThat(SensitiveDataRedactor.redact("User is already a member of this group"))
                .isEqualTo("User is already a member of this group");
    }

    @Test
    @DisplayName("handles null and blank")
    void handlesNullAndBlank() {
        assertThat(SensitiveDataRedactor.redact(null)).isNull();
        assertThat(SensitiveDataRedactor.redact("")).isEmpty();
    }
}
