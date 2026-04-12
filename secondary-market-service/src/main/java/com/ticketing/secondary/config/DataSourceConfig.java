package com.ticketing.secondary.config;

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

@Configuration
public class DataSourceConfig {

    @Value("${db.master.url}") private String masterUrl;
    @Value("${db.slave.url}")  private String slaveUrl;
    @Value("${db.username}")   private String username;
    @Value("${db.password}")   private String password;

    private static final String MASTER = "master";
    private static final String SLAVE  = "slave";

    @Bean public DataSource masterDataSource() { return build(masterUrl, MASTER, 10); }
    @Bean public DataSource slaveDataSource()  { return build(slaveUrl,  SLAVE,  5);  }

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

    private HikariDataSource build(String url, String name, int poolSize) {
        var cfg = new com.zaxxer.hikari.HikariConfig();
        cfg.setJdbcUrl(url); cfg.setUsername(username); cfg.setPassword(password);
        cfg.setDriverClassName("org.postgresql.Driver");
        cfg.setPoolName("HikariPool-" + name);
        cfg.setMaximumPoolSize(poolSize); cfg.setMinimumIdle(2);
        cfg.setConnectionTimeout(5000); cfg.setIdleTimeout(300_000); cfg.setMaxLifetime(900_000);
        cfg.addDataSourceProperty("cachePrepStmts", "true");
        cfg.addDataSourceProperty("prepStmtCacheSize", "250");
        return new HikariDataSource(cfg);
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
