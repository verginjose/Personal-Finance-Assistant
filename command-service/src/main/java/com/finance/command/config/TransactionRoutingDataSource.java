package com.finance.command.config;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Routes every JDBC connection request to either the write pool or the read pool
 * based on whether the current Spring transaction is marked readOnly.
 *
 * How it works:
 *   1. Spring opens a transaction and calls @Transactional (or @Transactional(readOnly=true)).
 *   2. Before the first JDBC connection is acquired, Spring calls determineCurrentLookupKey().
 *   3. We check TransactionSynchronizationManager.isCurrentTransactionReadOnly():
 *        - true  → returns DataSourceType.READ  → HikariPool-read  (20 connections)
 *        - false → returns DataSourceType.WRITE → HikariPool-write (8 connections)
 *
 * No changes needed in TransactionEntryService — it already uses
 * @Transactional(readOnly = true) on all read methods.
 */
public class TransactionRoutingDataSource extends AbstractRoutingDataSource {

    public enum DataSourceType { READ, WRITE }

    @Override
    protected Object determineCurrentLookupKey() {
        boolean isReadOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        return isReadOnly ? DataSourceType.READ : DataSourceType.WRITE;
    }
}
