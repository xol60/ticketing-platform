package com.ticketing.payment.mapper;

import com.ticketing.payment.domain.model.Payment;
import com.ticketing.payment.dto.response.PaymentResponse;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface PaymentMapper {

    PaymentResponse toResponse(Payment payment);
}
