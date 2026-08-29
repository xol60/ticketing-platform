package com.ticketing.agent;

import com.ticketing.agent.config.AgentProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Event recommendation agent.
 *
 * <p>A recommendation funnel with state, not a booking agent. The terminal
 * action is handing an {@code eventId} back to the caller; the user then goes
 * through the existing checkout flow untouched. This service never reserves a
 * ticket, never creates an order, and never takes a lock — so no saga, no
 * compensation, and no path by which a bug here can corrupt an order.
 *
 * <p>There is no agent loop. The action space is closed and small (search,
 * select, compare, hand off) and no action has a side effect, so an
 * orchestrator choosing its own next tool would buy nothing but
 * non-determinism. Control flow lives in Java; the model sits at three fixed
 * call sites and does one narrow job at each:
 *
 * <ul>
 *   <li><b>ingestion</b> — description → tags + facets. Offline, once per event.</li>
 *   <li><b>extract</b> — message → state patch + query facets. Hot path.</li>
 *   <li><b>compare</b> — two or three structured projections → prose. Rare.</li>
 * </ul>
 *
 * The model never emits a fact about an event — no time, no price, no venue.
 * It handles ids and vocabulary; rendering reads from the database.
 */
@SpringBootApplication(scanBasePackages = {"com.ticketing.agent", "com.ticketing.common"})
@EnableConfigurationProperties(AgentProperties.class)
public class AgentServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgentServiceApplication.class, args);
    }
}
