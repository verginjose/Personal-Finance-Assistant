package com.finance.command.controller;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ReceiptOwnershipTest {
    private final UUID owner = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private final UUID other = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");

    @Test
    void acceptsExistingSingleAndBatchUploadNamesOnlyForUploader() {
        assertTrue(ReceiptOwnership.belongsTo("/api/storage/receipts/" + owner + "/receipt.png", owner));
        assertTrue(ReceiptOwnership.belongsTo("/api/storage/receipts/" + owner + "-batch-abc/statement.pdf", owner));
        assertFalse(ReceiptOwnership.belongsTo("/api/storage/receipts/" + owner + "/receipt.png", other));
        assertFalse(ReceiptOwnership.belongsTo("/api/storage/profiles/" + owner + "/avatar.png", owner));
        assertFalse(ReceiptOwnership.belongsTo("/api/storage/receipts/" + owner + "-forged/file.pdf", owner));
    }
}
