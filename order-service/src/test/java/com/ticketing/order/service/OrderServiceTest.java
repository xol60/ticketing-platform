package com.ticketing.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.common.events.*;
import com.ticketing.order.client.EventValidationClient;
import com.ticketing.order.domain.model.Order;
import com.ticketing.order.domain.model.OrderStatus;
import com.ticketing.order.domain.repository.OrderRepository;
import com.ticketing.order.dto.request.CreateOrderRequest;
import com.ticketing.order.dto.response.OrderResponse;
import com.ticketing.order.kafka.OrderEventPublisher;
import com.ticketing.order.mapper.OrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrderService")
class OrderServiceTest {

    @Mock OrderRepository        orderRepository;
    @Mock OrderMapper            orderMapper;
    @Mock OrderEventPublisher    eventPublisher;
    @Mock RedisTemplate<String, String> redisTemplate;
    @Mock ValueOperations<String, String> valueOps;
    @Mock ObjectMapper           objectMapper;
    @Mock EventValidationClient  eventValidationClient;

    @InjectMocks OrderService orderService;

    private static final String USER_ID   = "user-001";
    private static final String ORDER_ID  = "order-001";
    private static final String TICKET_ID = "ticket-001";
    private static final String SAGA_ID   = "saga-001";
    private static final String TRACE_ID  = "trace-001";

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // createOrder
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("createOrder()")
    class CreateOrder {

        @Test
        @DisplayName("creates order and publishes OrderCreatedEvent when event is open")
        void creates_order_when_event_open() {
            var request = new CreateOrderRequest();
            request.setTicketId(TICKET_ID);
            request.setRequestedPrice(new BigDecimal("99.00"));

            when(eventValidationClient.isEventOpenForSales(TICKET_ID)).thenReturn(true);
            when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(orderMapper.toResponse(any())).thenReturn(new OrderResponse());

            orderService.createOrder(USER_ID, TRACE_ID, request);

            verify(orderRepository).save(any(Order.class));
            verify(eventPublisher).publishOrderCreated(
                    eq(TRACE_ID), anyString(), anyString(),
                    eq(USER_ID), eq(TICKET_ID),
                    eq(new BigDecimal("99.00")), any(Instant.class));
        }

        @Test
        @DisplayName("throws IllegalStateException when event is closed for sales")
        void rejects_order_when_event_closed() {
            var request = new CreateOrderRequest();
            request.setTicketId(TICKET_ID);
            request.setRequestedPrice(new BigDecimal("99.00"));

            when(eventValidationClient.isEventOpenForSales(TICKET_ID)).thenReturn(false);

            assertThatThrownBy(() -> orderService.createOrder(USER_ID, TRACE_ID, request))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not open for sales");

            verify(orderRepository, never()).save(any());
            verify(eventPublisher, never()).publishOrderCreated(any(), any(), any(), any(), any(), any(), any());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // handleConfirmed
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("handleConfirmed()")
    class HandleConfirmed {

        @Test
        @DisplayName("sets status CONFIRMED and stores finalPrice + paymentReference")
        void sets_confirmed_status() {
            Order order = buildOrder(OrderStatus.PENDING);
            when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
            when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var event = new OrderConfirmedEvent(
                    TRACE_ID, SAGA_ID, ORDER_ID, USER_ID, TICKET_ID,
                    new BigDecimal("99.00"), "PAY-REF-001");

            orderService.handleConfirmed(event);

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(order.getFinalPrice()).isEqualByComparingTo("99.00");
            assertThat(order.getPaymentReference()).isEqualTo("PAY-REF-001");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // handleFailed
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("handleFailed()")
    class HandleFailed {

        @Test
        @DisplayName("sets status FAILED and stores failure reason")
        void sets_failed_status() {
            Order order = buildOrder(OrderStatus.PENDING);
            when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
            when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var event = new OrderFailedEvent(
                    TRACE_ID, SAGA_ID, ORDER_ID, USER_ID, TICKET_ID, "Payment declined");

            orderService.handleFailed(event);

            assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
            assertThat(order.getFailureReason()).isEqualTo("Payment declined");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // handlePriceChanged
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("handlePriceChanged()")
    class HandlePriceChanged {

        @Test
        @DisplayName("sets status PRICE_CHANGED and stores pendingPrice")
        void sets_price_changed_status() {
            Order order = buildOrder(OrderStatus.PENDING);
            when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
            when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var event = new OrderPriceChangedEvent(
                    TRACE_ID, SAGA_ID, ORDER_ID, USER_ID,
                    new BigDecimal("99.00"), new BigDecimal("120.00"),
                    Instant.now().plusSeconds(300));

            orderService.handlePriceChanged(event);

            assertThat(order.getStatus()).isEqualTo(OrderStatus.PRICE_CHANGED);
            assertThat(order.getPendingPrice()).isEqualByComparingTo("120.00");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // handleCancelled
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("handleCancelled()")
    class HandleCancelled {

        @Test
        @DisplayName("sets status CANCELLED and stores reason")
        void sets_cancelled_status() {
            Order order = buildOrder(OrderStatus.PRICE_CHANGED);
            when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
            when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var event = new OrderCancelledEvent(
                    TRACE_ID, SAGA_ID, ORDER_ID, USER_ID, TICKET_ID, "User rejected price change");

            orderService.handleCancelled(event);

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(order.getFailureReason()).isEqualTo("User rejected price change");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // confirmPrice
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("confirmPrice()")
    class ConfirmPrice {

        @Test
        @DisplayName("sets order back to PENDING and publishes OrderPriceConfirmCommand")
        void confirm_price_success() {
            Order order = buildOrder(OrderStatus.PRICE_CHANGED);
            when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
            when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            orderService.confirmPrice(ORDER_ID, USER_ID, TRACE_ID);

            assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
            verify(eventPublisher).publishPriceConfirm(eq(TRACE_ID), eq(SAGA_ID), eq(ORDER_ID), eq(USER_ID));
        }

        @Test
        @DisplayName("throws when order is not in PRICE_CHANGED status")
        void confirm_price_wrong_status() {
            Order order = buildOrder(OrderStatus.PENDING); // wrong status
            when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

            assertThatThrownBy(() -> orderService.confirmPrice(ORDER_ID, USER_ID, TRACE_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not awaiting price confirmation");

            verify(eventPublisher, never()).publishPriceConfirm(any(), any(), any(), any());
        }

        @Test
        @DisplayName("throws when order belongs to different user")
        void confirm_price_wrong_user() {
            Order order = buildOrder(OrderStatus.PRICE_CHANGED);
            when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

            assertThatThrownBy(() -> orderService.confirmPrice(ORDER_ID, "other-user", TRACE_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not belong to user");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // cancelPrice
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("cancelPrice()")
    class CancelPrice {

        @Test
        @DisplayName("sets order to CANCELLED and publishes OrderPriceCancelCommand")
        void cancel_price_success() {
            Order order = buildOrder(OrderStatus.PRICE_CHANGED);
            when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
            when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            orderService.cancelPrice(ORDER_ID, USER_ID, TRACE_ID);

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(order.getFailureReason()).contains("rejected");
            verify(eventPublisher).publishPriceCancel(eq(TRACE_ID), eq(SAGA_ID), eq(ORDER_ID), eq(USER_ID));
        }

        @Test
        @DisplayName("throws when order is not in PRICE_CHANGED status")
        void cancel_price_wrong_status() {
            Order order = buildOrder(OrderStatus.CONFIRMED); // already done
            when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

            assertThatThrownBy(() -> orderService.cancelPrice(ORDER_ID, USER_ID, TRACE_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not awaiting price confirmation");
        }

        @Test
        @DisplayName("throws when order belongs to different user")
        void cancel_price_wrong_user() {
            Order order = buildOrder(OrderStatus.PRICE_CHANGED);
            when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

            assertThatThrownBy(() -> orderService.cancelPrice(ORDER_ID, "hacker", TRACE_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not belong to user");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private Order buildOrder(OrderStatus status) {
        Order order = new Order();
        order.setId(ORDER_ID);
        order.setUserId(USER_ID);
        order.setTicketId(TICKET_ID);
        order.setSagaId(SAGA_ID);
        order.setStatus(status);
        order.setRequestedPrice(new BigDecimal("99.00"));
        return order;
    }
}
