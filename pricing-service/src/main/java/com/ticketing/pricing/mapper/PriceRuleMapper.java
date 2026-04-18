package com.ticketing.pricing.mapper;

import com.ticketing.pricing.domain.model.EventPriceRule;
import com.ticketing.pricing.dto.request.CreatePriceRuleRequest;
import com.ticketing.pricing.dto.response.PriceRuleResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

import com.ticketing.pricing.dto.request.UpdatePriceRuleRequest;

@Mapper(
        componentModel = "spring",
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE
)
public interface PriceRuleMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "demandFactor", constant = "0.0")
    @Mapping(target = "soldTickets", constant = "0")
    @Mapping(target = "surgeMultiplier", expression = "java(java.math.BigDecimal.ONE)")
    EventPriceRule toEntity(CreatePriceRuleRequest request);

    @Mapping(target = "id", expression = "java(rule.getId().toString())")
    PriceRuleResponse toResponse(EventPriceRule rule);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "eventId", ignore = true)
    @Mapping(target = "surgeMultiplier", ignore = true)
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "demandFactor", ignore = true)
    @Mapping(target = "soldTickets", ignore = true)
    void updateEntity(UpdatePriceRuleRequest request, @MappingTarget EventPriceRule rule);
}
