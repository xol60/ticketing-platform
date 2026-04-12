package com.ticketing.saga.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SagaState {

    private String     sagaId;
    private String     orderId;
    private String     userId;
    private String     ticketId;
    private String     eventId;
    /** Price user submitted at order creation time. */
    private BigDecimal userPrice;
    /** DB-stamped order creation time for price_history lookup. */
    private Instant    orderCreatedAt;
    /** Price being awaited for confirmation (AWAITING_PRICE_CONFIRMATION state). */
    private BigDecimal pendingPrice;
    /** Final locked price after saga completes pricing step. */
    private BigDecimal lockedPrice;
    private String     paymentReference;
    private SagaStatus status;
    private String     currentStep;
    private String     failureReason;
    private Instant    startedAt;
    private Instant    lastUpdatedAt;

    public SagaState() {}

    public String getSagaId()                { return sagaId; }
    public String getOrderId()               { return orderId; }
    public String getUserId()                { return userId; }
    public String getTicketId()              { return ticketId; }
    public String getEventId()               { return eventId; }
    public BigDecimal getUserPrice()         { return userPrice; }
    public Instant getOrderCreatedAt()       { return orderCreatedAt; }
    public BigDecimal getPendingPrice()      { return pendingPrice; }
    public BigDecimal getLockedPrice()       { return lockedPrice; }
    public String getPaymentReference()      { return paymentReference; }
    public SagaStatus getStatus()            { return status; }
    public String getCurrentStep()           { return currentStep; }
    public String getFailureReason()         { return failureReason; }
    public Instant getStartedAt()            { return startedAt; }
    public Instant getLastUpdatedAt()        { return lastUpdatedAt; }

    public void setSagaId(String v)              { this.sagaId = v; }
    public void setOrderId(String v)             { this.orderId = v; }
    public void setUserId(String v)              { this.userId = v; }
    public void setTicketId(String v)            { this.ticketId = v; }
    public void setEventId(String v)             { this.eventId = v; }
    public void setUserPrice(BigDecimal v)       { this.userPrice = v; }
    public void setOrderCreatedAt(Instant v)     { this.orderCreatedAt = v; }
    public void setPendingPrice(BigDecimal v)     { this.pendingPrice = v; }
    public void setLockedPrice(BigDecimal v)      { this.lockedPrice = v; }
    public void setPaymentReference(String v)     { this.paymentReference = v; }
    public void setStatus(SagaStatus v)           { this.status = v; }
    public void setCurrentStep(String v)          { this.currentStep = v; }
    public void setFailureReason(String v)        { this.failureReason = v; }
    public void setStartedAt(Instant v)           { this.startedAt = v; }
    public void setLastUpdatedAt(Instant v)       { this.lastUpdatedAt = v; }

    @Override
    public String toString() {
        return "SagaState{sagaId='" + sagaId + "', orderId='" + orderId +
               "', status=" + status + ", step='" + currentStep + "'}";
    }
}
