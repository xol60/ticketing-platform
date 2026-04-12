package com.ticketing.reservation.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import com.ticketing.common.datasource.DataSourceRoutingContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * Master/slave routing DataSource.
 * Routes to slave for @Transactional(readOnly=true), master otherwise.
 */
@Configuration
public class DataSourceConfig {

    @Value("${db.master.url}")
    private String masterUrl;

    @Value("${db.slave.url}")
    private String slaveUrl;

    @Value("${db.username}")
    private String username;

    @Value("${db.password}")
    private String password;

    private static final String MASTER = "master";
    private static final String SLAVE  = "slave";

    @Bean
    public DataSource masterDataSource() {
        return buildDataSource(masterUrl, MASTER, 10);
    }

    @Bean
    public DataSource slaveDataSource() {
        return buildDataSource(slaveUrl, SLAVE, 5);
    }

    @Primary
    @Bean
    public DataSource routingDataSource() {
        var routing = new RoutingDataSource();

        Map<Object, Object> targets = new HashMap<>();
        targets.put(MASTER, masterDataSource());
        targets.put(SLAVE,  slaveDataSource());

        routing.setTargetDataSources(targets);
        routing.setDefaultTargetDataSource(masterDataSource());
        routing.afterPropertiesSet();
        return routing;
    }

    private HikariDataSource buildDataSource(String url, String poolName, int poolSize) {
        var config = new com.zaxxer.hikari.HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName("org.postgresql.Driver");
        config.setPoolName("HikariPool-" + poolName);
        config.setMaximumPoolSize(poolSize);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(5000);
        config.setIdleTimeout(300000);
        config.setMaxLifetime(900000);
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        return new HikariDataSource(config);
    }

    /**
     * Routing priority:
     *  1. Explicit ThreadLocal override (sticky-master after recent write)
     *  2. Transaction readOnly flag
     *  3. Default → master
     */
    static class RoutingDataSource extends AbstractRoutingDataSource {
        @Override
        protected Object determineCurrentLookupKey() {
            // Priority 1: explicit override (set by ReplicationConsistencyAspect)
            DataSourceRoutingContext.Route override = DataSourceRoutingContext.get();
            if (override == DataSourceRoutingContext.Route.MASTER) {
                return MASTER;
            }
            // Priority 2: transaction readOnly flag
            return TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                    ? SLAVE : MASTER;
        }
    }
}
