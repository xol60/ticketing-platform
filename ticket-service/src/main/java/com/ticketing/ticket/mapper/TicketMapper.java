package com.ticketing.ticket.mapper;

import com.ticketing.ticket.domain.model.Ticket;
import com.ticketing.ticket.dto.request.CreateTicketRequest;
import com.ticketing.ticket.dto.response.TicketResponse;
import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

@Mapper(
    componentModel = "spring",
    nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE
)
public interface TicketMapper {

    Ticket toEntity(CreateTicketRequest request);

    TicketResponse toResponse(Ticket ticket);

    void updateEntityFromRequest(com.ticketing.ticket.dto.request.UpdateTicketRequest request,
                                 @MappingTarget Ticket ticket);
}
