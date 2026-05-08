package com.ticketing.saga.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.common.events.*;
import com.ticketing.saga.model.SagaState;
import com.ticketing.saga.model.SagaStatus;
import com.ticketing.saga.service.SagaStateStore;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Saga flow integration tests.
 *
 * Strategy:
 *  - Real Kafka (Testcontainers) + real Redis (Testcontainers)
 *  - Spring Boot context started once for all tests
 *  - Tests publish domain events directly to Kafka and then inspect:
 *      1. The saga state in Redis (via SagaStateStore)
 *      2. The commands/events published back onto Kafka by the orchestrator
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@ActiveProfiles("integration-test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SagaFlowIntegrationTest {

    // ── Containers ────────────────────────────────────────────────────────────

    @Container
    static KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    @SuppressWarnings("resource")
    @Container
    static GenericContainer<?> redis = new GenericContainer<>(
            DockerImageName.parse("redis:7.2-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    // ── Injected beans ────────────────────────────────────────────────────────

    @Autowired SagaStateStore stateStore;
    @Autowired ObjectMapper   objectMapper;

    // ── Shared test fixtures ──────────────────────────────────────────────────

    private static final String ORDER_ID  = "it-order-001";
    private static final String USER_ID   = "it-user-001";
    private static final String TICKET_ID = "it-ticket-001";
    private static final String EVENT_ID  = "it-event-001";
    private static final String SAGA_ID   = "it-saga-001";
    private static final String TRACE_ID  = "it-trace-001";

    private KafkaProducer<String, DomainEvent> producer;
    private KafkaConsumer<String, DomainEvent> consumer;

    @BeforeEach
    void setUpKafkaClients() {
        producer = buildProducer();
        consumer = buildConsumer("saga-it-verifier-" + UUID.randomUUID());
        consumer.subscribe(List.of(
                Topics.TICKET_CMD,          // reserve + confirm + release (unified)
                Topics.PRICING_LOCK_CMD,
                Topics.PAYMENT_CMD,         // charge + cancel (unified)
                Topics.PRICING_UNLOCK_CMD,
                Topics.ORDER_CONFIRMED,
                Topics.ORDER_FAILED,
                Topics.ORDER_CANCELLED,
                Topics.ORDER_PRICE_CHANGED
        ));
    }

    @AfterEach
    void tearDownKafkaClients() {
        producer.close();
        consumer.close();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Flow 1 — Happy path
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("Happy path: OrderCreated → TicketReserved → PricingLocked → PaymentSucceeded → TicketConfirmed → COMPLETED")
    void happyPath_fullFlow() throws Exception {
        String sagaId = "happy-" + UUID.randomUUID();

        // Step 1: publish OrderCreatedEvent
        publish(Topics.ORDER_CREATED, ORDER_ID,
                new OrderCreatedEvent(TRACE_ID, sagaId, ORDER_ID, USER_ID,
                        TICKET_ID, new BigDecimal("99.00"), Instant.now()));

        // Orchestrator should send TicketReserveCommand
        DomainEvent reserveCmd = pollForEvent(Topics.TICKET_CMD, sagaId);
        assertThat(reserveCmd).isInstanceOf(TicketReserveCommand.class);
        assertSagaStatus(sagaId, SagaStatus.STARTED);

        // Step 2: simulate ticket-service response
        publish(Topics.TICKET_RESERVED, TICKET_ID,
                new TicketReservedEvent(TRACE_ID, sagaId, TICKET_ID, ORDER_ID, new BigDecimal("99.00")));

        // Orchestrator should send PriceLockCommand
        DomainEvent lockCmd = pollForEvent(Topics.PRICING_LOCK_CMD, sagaId);
        assertThat(lockCmd).isInstanceOf(PriceLockCommand.class);
        assertThat(((PriceLockCommand) lockCmd).isConfirmed()).isFalse();
        assertSagaStatus(sagaId, SagaStatus.TICKET_RESERVED);

        // Step 3: simulate pricing-service response
        publish(Topics.PRICING_LOCKED, TICKET_ID,
                new PricingLockedEvent(TRACE_ID, sagaId, TICKET_ID, ORDER_ID, new BigDecimal("99.00")));

        // Orchestrator should send PaymentChargeCommand
        DomainEvent chargeCmd = pollForEvent(Topics.PAYMENT_CMD, sagaId);
        assertThat(chargeCmd).isInstanceOf(PaymentChargeCommand.class);
        assertThat(((PaymentChargeCommand) chargeCmd).getAmount()).isEqualByComparingTo("99.00");
        assertSagaStatus(sagaId, SagaStatus.PRICING_LOCKED);

        // Step 4: simulate payment-service response
        publish(Topics.PAYMENT_SUCCEEDED, ORDER_ID,
                new PaymentSucceededEvent(TRACE_ID, sagaId, ORDER_ID, USER_ID,
                        new BigDecimal("99.00"), "PAY-REF-001"));

        // Orchestrator should send TicketConfirmCommand
        DomainEvent confirmCmd = pollForEvent(Topics.TICKET_CMD, sagaId);
        assertThat(confirmCmd).isInstanceOf(TicketConfirmCommand.class);
        assertSagaStatus(sagaId, SagaStatus.PAYMENT_CHARGED);

        // Step 5: simulate ticket-service confirmation
        publish(Topics.TICKET_CONFIRMED, TICKET_ID,
                new TicketConfirmedEvent(TRACE_ID, sagaId, TICKET_ID, ORDER_ID));

        // Orchestrator should publish OrderConfirmedEvent
        DomainEvent orderConfirmed = pollForEvent(Topics.ORDER_CONFIRMED, sagaId);
        assertThat(orderConfirmed).isInstanceOf(OrderConfirmedEvent.class);
        OrderConfirmedEvent confirmed = (OrderConfirmedEvent) orderConfirmed;
        assertThat(confirmed.getOrderId()).isEqualTo(ORDER_ID);
        assertThat(confirmed.getFinalPrice()).isEqualByComparingTo("99.00");
        assertThat(confirmed.getPaymentReference()).isEqualTo("PAY-REF-001");

        assertSagaStatus(sagaId, SagaStatus.COMPLETED);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Flow 2 — Fabricated price (Case A) → CANCELLED
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @Order(2)
    @DisplayName("Fabricated price: PricingFailed(INVALID_PRICE) → ticket released → OrderCancelled")
    void fabricatedPrice_cancelsSaga() throws Exception {
        String sagaId = "fake-price-" + UUID.randomUUID();

        publish(Topics.ORDER_CREATED, ORDER_ID,
                new OrderCreatedEvent(TRACE_ID, sagaId, ORDER_ID, USER_ID,
                        TICKET_ID, new BigDecimal("1.00"), Instant.now())); // fake price

        // Wait for TicketReserveCommand
        pollForEvent(Topics.TICKET_CMD, sagaId);

        // Simulate ticket reserved
        publish(Topics.TICKET_RESERVED, TICKET_ID,
                new TicketReservedEvent(TRACE_ID, sagaId, TICKET_ID, ORDER_ID, new BigDecimal("1.00")));

        // Pricing service detects fabricated price
        publish(Topics.PRICING_FAILED, ORDER_ID,
                new PricingFailedEvent(TRACE_ID, sagaId, ORDER_ID, TICKET_ID, "INVALID_PRICE"));

        // Orchestrator should release the ticket
        DomainEvent releaseCmd = pollForEvent(Topics.TICKET_CMD, sagaId);
        assertThat(releaseCmd).isInstanceOf(TicketReleaseCommand.class);

        // Orchestrator should publish OrderCancelledEvent
        DomainEvent cancelledEvent = pollForEvent(Topics.ORDER_CANCELLED, sagaId);
        assertThat(cancelledEvent).isInstanceOf(OrderCancelledEvent.class);
        assertThat(((OrderCancelledEvent) cancelledEvent).getOrderId()).isEqualTo(ORDER_ID);

        assertSagaStatus(sagaId, SagaStatus.CANCELLED);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Flow 3 — Price changed, user confirms → COMPLETED
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @Order(3)
    @DisplayName("Price changed → user confirms → saga resumes → COMPLETED")
    void priceChanged_userConfirms_resumesFlow() throws Exception {
        String sagaId = "price-confirm-" + UUID.randomUUID();

        publish(Topics.ORDER_CREATED, ORDER_ID,
                new OrderCreatedEvent(TRACE_ID, sagaId, ORDER_ID, USER_ID,
                        TICKET_ID, new BigDecimal("99.00"), Instant.now()));

        pollForEvent(Topics.TICKET_CMD, sagaId);

        publish(Topics.TICKET_RESERVED, TICKET_ID,
                new TicketReservedEvent(TRACE_ID, sagaId, TICKET_ID, ORDER_ID, new BigDecimal("99.00")));

        // Pricing detects stale price
        publish(Topics.PRICING_PRICE_CHANGED, ORDER_ID,
                new PriceChangedEvent(TRACE_ID, sagaId, ORDER_ID, TICKET_ID,
                        new BigDecimal("99.00"), new BigDecimal("120.00"),
                        Instant.now().plusSeconds(300)));

        // Orchestrator notifies order-service
        DomainEvent priceChangedEvent = pollForEvent(Topics.ORDER_PRICE_CHANGED, sagaId);
        assertThat(priceChangedEvent).isInstanceOf(OrderPriceChangedEvent.class);
        assertThat(((OrderPriceChangedEvent) priceChangedEvent).getNewPrice())
                .isEqualByComparingTo("120.00");
        assertSagaStatus(sagaId, SagaStatus.AWAITING_PRICE_CONFIRMATION);

        // User confirms the new price
        publish(Topics.ORDER_PRICE_CONFIRM, ORDER_ID,
                new OrderPriceConfirmCommand(TRACE_ID, sagaId, ORDER_ID, USER_ID));

        // Orchestrator re-sends PriceLockCommand with confirmed=true
        DomainEvent relockCmd = pollForEvent(Topics.PRICING_LOCK_CMD, sagaId);
        assertThat(relockCmd).isInstanceOf(PriceLockCommand.class);
        assertThat(((PriceLockCommand) relockCmd).isConfirmed()).isTrue();
        assertThat(((PriceLockCommand) relockCmd).getUserPrice()).isEqualByComparingTo("120.00");

        // Pricing locks at new price
        publish(Topics.PRICING_LOCKED, TICKET_ID,
                new PricingLockedEvent(TRACE_ID, sagaId, TICKET_ID, ORDER_ID, new BigDecimal("120.00")));

        // Payment charged
        DomainEvent chargeCmd = pollForEvent(Topics.PAYMENT_CMD, sagaId);
        assertThat(((PaymentChargeCommand) chargeCmd).getAmount()).isEqualByComparingTo("120.00");
        assertSagaStatus(sagaId, SagaStatus.PRICING_LOCKED);

        publish(Topics.PAYMENT_SUCCEEDED, ORDER_ID,
                new PaymentSucceededEvent(TRACE_ID, sagaId, ORDER_ID, USER_ID,
                        new BigDecimal("120.00"), "PAY-REF-002"));

        pollForEvent(Topics.TICKET_CMD, sagaId);

        publish(Topics.TICKET_CONFIRMED, TICKET_ID,
                new TicketConfirmedEvent(TRACE_ID, sagaId, TICKET_ID, ORDER_ID));

        DomainEvent orderConfirmed = pollForEvent(Topics.ORDER_CONFIRMED, sagaId);
        assertThat(((OrderConfirmedEvent) orderConfirmed).getFinalPrice())
                .isEqualByComparingTo("120.00");
        assertSagaStatus(sagaId, SagaStatus.COMPLETED);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Flow 4 — Price changed, user rejects → CANCELLED
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @Order(4)
    @DisplayName("Price changed → user cancels → ticket released → OrderCancelled")
    void priceChanged_userCancels_cancelsSaga() throws Exception {
        String sagaId = "price-cancel-" + UUID.randomUUID();

        publish(Topics.ORDER_CREATED, ORDER_ID,
                new OrderCreatedEvent(TRACE_ID, sagaId, ORDER_ID, USER_ID,
                        TICKET_ID, new BigDecimal("99.00"), Instant.now()));

        pollForEvent(Topics.TICKET_CMD, sagaId);

        publish(Topics.TICKET_RESERVED, TICKET_ID,
                new TicketReservedEvent(TRACE_ID, sagaId, TICKET_ID, ORDER_ID, new BigDecimal("99.00")));

        publish(Topics.PRICING_PRICE_CHANGED, ORDER_ID,
                new PriceChangedEvent(TRACE_ID, sagaId, ORDER_ID, TICKET_ID,
                        new BigDecimal("99.00"), new BigDecimal("120.00"),
                        Instant.now().plusSeconds(300)));

        pollForEvent(Topics.ORDER_PRICE_CHANGED, sagaId);
        assertSagaStatus(sagaId, SagaStatus.AWAITING_PRICE_CONFIRMATION);

        // User rejects the new price
        publish(Topics.ORDER_PRICE_CANCEL, ORDER_ID,
                new OrderPriceCancelCommand(TRACE_ID, sagaId, ORDER_ID, USER_ID));

        // Ticket must be released
        DomainEvent releaseCmd = pollForEvent(Topics.TICKET_CMD, sagaId);
        assertThat(releaseCmd).isInstanceOf(TicketReleaseCommand.class);

        // Order cancelled event published
        DomainEvent cancelledEvent = pollForEvent(Topics.ORDER_CANCELLED, sagaId);
        assertThat(cancelledEvent).isInstanceOf(OrderCancelledEvent.class);
        assertThat(((OrderCancelledEvent) cancelledEvent).getReason()).contains("rejected");

        assertSagaStatus(sagaId, SagaStatus.CANCELLED);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Flow 5 — Payment failed → compensation → FAILED
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @Order(5)
    @DisplayName("Payment failed → saga compensates → OrderFailed published")
    void paymentFailed_compensatesSaga() throws Exception {
        String sagaId = "pay-fail-" + UUID.randomUUID();

        publish(Topics.ORDER_CREATED, ORDER_ID,
                new OrderCreatedEvent(TRACE_ID, sagaId, ORDER_ID, USER_ID,
                        TICKET_ID, new BigDecimal("99.00"), Instant.now()));

        pollForEvent(Topics.TICKET_CMD, sagaId);

        publish(Topics.TICKET_RESERVED, TICKET_ID,
                new TicketReservedEvent(TRACE_ID, sagaId, TICKET_ID, ORDER_ID, new BigDecimal("99.00")));

        pollForEvent(Topics.PRICING_LOCK_CMD, sagaId);

        publish(Topics.PRICING_LOCKED, TICKET_ID,
                new PricingLockedEvent(TRACE_ID, sagaId, TICKET_ID, ORDER_ID, new BigDecimal("99.00")));

        pollForEvent(Topics.PAYMENT_CMD, sagaId);

        // Payment fails
        publish(Topics.PAYMENT_FAILED, ORDER_ID,
                new PaymentFailedEvent(TRACE_ID, sagaId, ORDER_ID, USER_ID, "Insufficient funds", 1));

        DomainEvent orderFailed = pollForEvent(Topics.ORDER_FAILED, sagaId);
        assertThat(orderFailed).isInstanceOf(OrderFailedEvent.class);
        assertThat(((OrderFailedEvent) orderFailed).getReason()).contains("Insufficient funds");

        assertSagaStatus(sagaId, SagaStatus.FAILED);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Flow 6 — Ticket released while PRICING_LOCKED → unlocks price → FAILED
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @Order(6)
    @DisplayName("Ticket released while PRICING_LOCKED → PriceUnlockCommand + OrderFailed")
    void ticketReleasedDuringSaga_unlocksPriceAndFails() throws Exception {
        String sagaId = "ticket-released-" + UUID.randomUUID();

        publish(Topics.ORDER_CREATED, ORDER_ID,
                new OrderCreatedEvent(TRACE_ID, sagaId, ORDER_ID, USER_ID,
                        TICKET_ID, new BigDecimal("99.00"), Instant.now()));

        pollForEvent(Topics.TICKET_CMD, sagaId);

        publish(Topics.TICKET_RESERVED, TICKET_ID,
                new TicketReservedEvent(TRACE_ID, sagaId, TICKET_ID, ORDER_ID, new BigDecimal("99.00")));

        pollForEvent(Topics.PRICING_LOCK_CMD, sagaId);

        publish(Topics.PRICING_LOCKED, TICKET_ID,
                new PricingLockedEvent(TRACE_ID, sagaId, TICKET_ID, ORDER_ID, new BigDecimal("99.00")));

        // Ticket gets released externally while saga is at PRICING_LOCKED
        publish(Topics.TICKET_RELEASED, TICKET_ID,
                new TicketReleasedEvent(TRACE_ID, sagaId, TICKET_ID, ORDER_ID, "Admin override"));

        // Must send price unlock command
        DomainEvent unlockCmd = pollForEvent(Topics.PRICING_UNLOCK_CMD, sagaId);
        assertThat(unlockCmd).isInstanceOf(PriceUnlockCommand.class);

        // Must publish order failed
        DomainEvent orderFailed = pollForEvent(Topics.ORDER_FAILED, sagaId);
        assertThat(orderFailed).isInstanceOf(OrderFailedEvent.class);

        assertSagaStatus(sagaId, SagaStatus.FAILED);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void publish(String topic, String key, DomainEvent event) throws Exception {
        producer.send(new ProducerRecord<>(topic, key, event)).get();
    }

    /**
     * Polls Kafka until we receive an event on the given topic that belongs to sagaId,
     * or times out after 15 seconds.
     */
    private DomainEvent pollForEvent(String expectedTopic, String sagaId) {
        Instant deadline = Instant.now().plusSeconds(15);
        while (Instant.now().isBefore(deadline)) {
            ConsumerRecords<String, DomainEvent> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, DomainEvent> record : records) {
                DomainEvent event = record.value();
                if (record.topic().equals(expectedTopic)
                        && sagaId.equals(event.getSagaId())) {
                    return event;
                }
            }
        }
        throw new AssertionError(
                "Timed out waiting for event on topic=" + expectedTopic + " sagaId=" + sagaId);
    }

    /**
     * Polls saga state from Redis until the expected status is reached or times out.
     */
    private void assertSagaStatus(String sagaId, SagaStatus expected) {
        Instant deadline = Instant.now().plusSeconds(10);
        SagaState state = null;
        while (Instant.now().isBefore(deadline)) {
            state = stateStore.load(sagaId);
            if (state != null && state.getStatus() == expected) return;
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        }
        String actual = state == null ? "null" : state.getStatus().name();
        throw new AssertionError(
                "Expected saga sagaId=" + sagaId + " to be " + expected + " but was " + actual);
    }

    private KafkaProducer<String, DomainEvent> buildProducer() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        return new KafkaProducer<>(props);
    }

    private KafkaConsumer<String, DomainEvent> buildConsumer(String groupId) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.ticketing.*");
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, DomainEvent.class.getName());
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        return new KafkaConsumer<>(props);
    }
}
