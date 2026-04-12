package com.ticketing.common.datasource;

/**
 * ThreadLocal holder for per-request DataSource routing override.
 *
 * Priority order in RoutingDataSource:
 *   1. Explicit override set here  (e.g. sticky-master after recent write)
 *   2. Transaction readOnly flag   (normal Spring routing)
 *   3. Default → master
 *
 * Always call clear() at the end of each request (done by ReplicationConsistencyAspect).
 */
public final class DataSourceRoutingContext {

    public enum Route { MASTER, SLAVE }

    private static final ThreadLocal<Route> CONTEXT = new ThreadLocal<>();

    private DataSourceRoutingContext() {}

    public static void forceMaster() {
        CONTEXT.set(Route.MASTER);
    }

    public static void clear() {
        CONTEXT.remove();
    }

    /**
     * Returns the explicit override if set, otherwise null (caller falls back to
     * transaction readOnly flag).
     */
    public static Route get() {
        return CONTEXT.get();
    }
}
