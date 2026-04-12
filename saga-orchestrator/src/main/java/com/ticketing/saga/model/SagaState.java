package com.ticketing.saga.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SagaState {

    private String sagaId;
    private String orderId;
    private String userId;
    private String ticketId;
    private String eventId;
    private BigDecimal lockedPrice;
    private String paymentReference;
    private SagaStatus status;
    private String currentStep;
    private String failureReason;
    private Instant startedAt;
    private Instant lastUpdatedAt;

    public SagaState() {}

    // ---- Getters ----

    public String getSagaId() { return sagaId; }
    public String getOrderId() { return orderId; }
    public String getUserId() { return userId; }
    public String getTicketId() { return ticketId; }
    public String getEventId() { return eventId; }
    public BigDecimal getLockedPrice() { return lockedPrice; }
    public String getPaymentReference() { return paymentReference; }
    public SagaStatus getStatus() { return status; }
    public String getCurrentStep() { return currentStep; }
    public String getFailureReason() { return failureReason; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getLastUpdatedAt() { return lastUpdatedAt; }

    // ---- Setters ----

    public void setSagaId(String sagaId) { this.sagaId = sagaId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public void setUserId(String userId) { this.userId = userId; }
    public void setTicketId(String ticketId) { this.ticketId = ticketId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public void setLockedPrice(BigDecimal lockedPrice) { this.lockedPrice = lockedPrice; }
    public void setPaymentReference(String paymentReference) { this.paymentReference = paymentReference; }
    public void setStatus(SagaStatus status) { this.status = status; }
    public void setCurrentStep(String currentStep) { this.currentStep = currentStep; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public void setLastUpdatedAt(Instant lastUpdatedAt) { this.lastUpdatedAt = lastUpdatedAt; }

    @Override
    public String toString() {
        return "SagaState{sagaId='" + sagaId + "', orderId='" + orderId +
               "', status=" + status + ", currentStep='" + currentStep + "'}";
    }
}
