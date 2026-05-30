package com.upsertservice.config;

import com.upsertservice.config.TransactionRoutingDataSource.DataSourceType;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

import javax.sql.DataSource;
import java.util.Map;

/**
 * Registers two separate HikariCP connection pools and wires them through
 * TransactionRoutingDataSource so that Spring's @Transactional(readOnly) flag
 * automatically selects the correct pool for each query.
 *
 * Pool strategy:
 *   - writeDataSource  → small pool (8 conns) → INSERT / UPDATE / DELETE
 *   - readDataSource   → large pool (20 conns) → SELECT queries
 *
 * LazyConnectionDataSourceProxy ensures a physical JDBC connection is not
 * acquired from the pool until the first actual SQL statement is executed,
 * preventing wasted connections on early-return / cached-result code paths.
 */
@Slf4j
@Configuration
public class DataSourceConfig {

    // ── Write pool properties ─────────────────────────────────────────────────

    @Value("${app.datasource.write.jdbc-url}")
    private String writeUrl;

    @Value("${app.datasource.write.username}")
    private String writeUsername;

    @Value("${app.datasource.write.password}")
    private String writePassword;

    @Value("${app.datasource.write.pool-name:HikariPool-write}")
    private String writePoolName;

    @Value("${app.datasource.write.maximum-pool-size:8}")
    private int writeMaxPoolSize;

    @Value("${app.datasource.write.minimum-idle:2}")
    private int writeMinIdle;

    @Value("${app.datasource.write.connection-timeout:3000}")
    private long writeConnectionTimeout;

    @Value("${app.datasource.write.max-lifetime:1800000}")
    private long writeMaxLifetime;

    // ── Read pool properties ──────────────────────────────────────────────────

    @Value("${app.datasource.read.jdbc-url}")
    private String readUrl;

    @Value("${app.datasource.read.username}")
    private String readUsername;

    @Value("${app.datasource.read.password}")
    private String readPassword;

    @Value("${app.datasource.read.pool-name:HikariPool-read}")
    private String readPoolName;

    @Value("${app.datasource.read.maximum-pool-size:20}")
    private int readMaxPoolSize;

    @Value("${app.datasource.read.minimum-idle:5}")
    private int readMinIdle;

    @Value("${app.datasource.read.connection-timeout:3000}")
    private long readConnectionTimeout;

    @Value("${app.datasource.read.max-lifetime:1800000}")
    private long readMaxLifetime;

    // ── Bean definitions ──────────────────────────────────────────────────────

    @Bean
    public HikariDataSource writeDataSource() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(writeUrl);
        cfg.setUsername(writeUsername);
        cfg.setPassword(writePassword);
        cfg.setPoolName(writePoolName);
        cfg.setMaximumPoolSize(writeMaxPoolSize);
        cfg.setMinimumIdle(writeMinIdle);
        cfg.setConnectionTimeout(writeConnectionTimeout);
        cfg.setMaxLifetime(writeMaxLifetime);
        cfg.setAutoCommit(false);            // Spring manages commits via @Transactional
        log.info("Initialized write connection pool: {} (max={})", writePoolName, writeMaxPoolSize);
        return new HikariDataSource(cfg);
    }

    @Bean
    public HikariDataSource readDataSource() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(readUrl);
        cfg.setUsername(readUsername);
        cfg.setPassword(readPassword);
        cfg.setPoolName(readPoolName);
        cfg.setMaximumPoolSize(readMaxPoolSize);
        cfg.setMinimumIdle(readMinIdle);
        cfg.setConnectionTimeout(readConnectionTimeout);
        cfg.setMaxLifetime(readMaxLifetime);
        cfg.setReadOnly(true);               // Hint to Postgres driver — skip dirty-check flush
        cfg.setAutoCommit(false);
        log.info("Initialized read connection pool: {} (max={})", readPoolName, readMaxPoolSize);
        return new HikariDataSource(cfg);
    }

    /**
     * The routing DataSource — delegates to writeDataSource or readDataSource
     * based on TransactionSynchronizationManager.isCurrentTransactionReadOnly().
     */
    @Bean
    public DataSource routingDataSource(HikariDataSource writeDataSource,
                                        HikariDataSource readDataSource) {
        TransactionRoutingDataSource routing = new TransactionRoutingDataSource();
        routing.setTargetDataSources(Map.of(
                DataSourceType.WRITE, writeDataSource,
                DataSourceType.READ,  readDataSource
        ));
        routing.setDefaultTargetDataSource(writeDataSource); // fallback for non-transactional calls
        routing.afterPropertiesSet();
        return routing;
    }

    /**
     * LazyConnectionDataSourceProxy wraps the routing datasource.
     * A real JDBC connection is only borrowed from the pool when the first
     * SQL is actually executed — not at @Transactional method entry.
     * This is critical for virtual threads to avoid holding pool connections
     * during non-SQL work (validation, mapping, etc.).
     *
     * Marked @Primary so Spring JPA / Hibernate auto-wires this bean.
     */
    @Bean
    @Primary
    public DataSource dataSource(DataSource routingDataSource) {
        return new LazyConnectionDataSourceProxy(routingDataSource);
    }
}
