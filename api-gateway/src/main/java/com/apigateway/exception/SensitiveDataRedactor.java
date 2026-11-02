package com.apigateway.exception;

import java.util.regex.Pattern;

/**
 * Masks server-side identifiers before an exception message is returned to a
 * client. Only the response body is filtered — the handlers still log the raw
 * message, so operators keep the full detail in the logs while the UI never
 * sees database ids, UUIDs or email addresses.
 */
public final class SensitiveDataRedactor {

    private static final String ID = "<id>";

    private static final Pattern UUID = Pattern.compile(
            "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");

    private static final Pattern EMAIL = Pattern.compile(
            "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b");

    /**
     * "Transaction entry with ID 156172", "Expense 178", "Group 388".
     * The second group keeps the label ("Group ", " with ID ") intact so the
     * message stays readable.
     */
    private static final Pattern ENTITY_WITH_ID = Pattern.compile(
            "(?i)\\b(transaction entry|transaction|expense|group|goal|budget|subscription"
                    + "|membership|entry|contribution|record|notification|split|user)"
                    + "(\\s*(?:with\\s+)?(?:id)?\\s*[:#]?\\s+)(\\d{1,19})\\b");

    /** "ID 42", "ids: 1,2" */
    private static final Pattern ID_LABEL = Pattern.compile(
            "(?i)\\b(ids?)(\\s*[:#]?\\s*)(\\d{1,19})\\b");

    /** "not found: 5" */
    private static final Pattern NOT_FOUND_COLON = Pattern.compile(
            "(?i)(not found\\s*:\\s*)(\\d{1,19})\\b");

    private SensitiveDataRedactor() {
    }

    public static String redact(String message) {
        if (message == null || message.isBlank()) {
            return message;
        }
        String out = UUID.matcher(message).replaceAll(ID);
        out = EMAIL.matcher(out).replaceAll("<email>");
        out = ENTITY_WITH_ID.matcher(out).replaceAll("$1$2" + ID);
        out = ID_LABEL.matcher(out).replaceAll("$1$2" + ID);
        out = NOT_FOUND_COLON.matcher(out).replaceAll("$1" + ID);
        return out;
    }
}
