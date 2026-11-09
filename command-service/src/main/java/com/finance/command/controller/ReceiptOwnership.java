package com.finance.command.controller;

import java.util.UUID;

/** Existing object names start with the uploader's UUID, followed by / or -batch-. */
public final class ReceiptOwnership {
    private ReceiptOwnership() {}

    public static boolean belongsTo(String url, UUID userId) {
        if (url == null || userId == null) return false;
        String path = "/api/storage/receipts/" + userId;
        return url.startsWith(path + "/") || url.startsWith(path + "-batch-");
    }

    public static void requireOwned(String url, UUID userId) {
        if (url != null && !url.isBlank() && !belongsTo(url, userId)) {
            throw new SecurityException("Receipt was not uploaded by this user");
        }
    }
}
