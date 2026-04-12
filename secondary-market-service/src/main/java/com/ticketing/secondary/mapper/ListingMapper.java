package com.ticketing.secondary.mapper;

import com.ticketing.secondary.domain.model.Listing;
import com.ticketing.secondary.dto.response.ListingResponse;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface ListingMapper {
    ListingResponse toResponse(Listing listing);
}
