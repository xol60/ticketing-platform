package com.ticketing.secondary.service;

import com.ticketing.common.events.OrderCreatedEvent;
import com.ticketing.secondary.client.EventValidationClient;
import com.ticketing.secondary.domain.model.Listing;
import com.ticketing.secondary.domain.model.ListingStatus;
import com.ticketing.secondary.domain.repository.ListingRepository;
import com.ticketing.secondary.dto.request.CreateListingRequest;
import com.ticketing.secondary.dto.response.ListingResponse;
import com.ticketing.secondary.kafka.SecondaryMarketEventPublisher;
import com.ticketing.secondary.mapper.ListingMapper;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SecondaryMarketService {

    private static final String L2_PREFIX = "listings:event:";

    private final ListingRepository             listingRepository;
    private final ListingMapper                 listingMapper;
    private final SecondaryMarketEventPublisher eventPublisher;
    private final RedisTemplate<String, String> redisTemplate;
    private final EventValidationClient         eventValidationClient;

    @Transactional
    @CacheEvict(value = "listings", key = "#request.eventId")
    public ListingResponse createListing(CreateListingRequest request, String sellerId) {
        Listing listing = Listing.builder()
                .ticketId(request.getTicketId())
                .sellerId(sellerId)
                .eventId(request.getEventId())
                .askPrice(request.getAskPrice())
                .status(ListingStatus.ACTIVE)
                .version(0L)
                .build();
        listing = listingRepository.save(listing);
        redisTemplate.delete(L2_PREFIX + request.getEventId());
        log.info("Created listing id={} ticketId={} seller={}", listing.getId(), listing.getTicketId(), sellerId);
        return listingMapper.toResponse(listing);
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "listings", key = "#eventId")
    public List<ListingResponse> getListingsByEvent(String eventId) {
        return listingRepository.findByEventIdAndStatus(eventId, ListingStatus.ACTIVE)
                .stream().map(listingMapper::toResponse).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public ListingResponse getListing(String id) {
        return listingMapper.toResponse(findOrThrow(id));
    }

    @Transactional
    @CacheEvict(value = "listings", key = "#result.eventId", condition = "#result != null")
    public ListingResponse cancelListing(String id, String userId) {
        Listing listing = findOrThrow(id);
        if (!listing.getSellerId().equals(userId)) {
            throw new IllegalArgumentException("Only the seller can cancel this listing");
        }
        if (listing.getStatus() != ListingStatus.ACTIVE) {
            throw new IllegalStateException("Listing is not ACTIVE: " + listing.getStatus());
        }
        listing.setStatus(ListingStatus.CANCELLED);
        listing = listingRepository.save(listing);
        redisTemplate.delete(L2_PREFIX + listing.getEventId());
        log.info("Cancelled listing id={}", id);
        return listingMapper.toResponse(listing);
    }

    @Transactional
    public ListingResponse purchaseListing(String id, String buyerId, String traceId) {
        Listing listing = listingRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new EntityNotFoundException("Listing not found: " + id));

        if (listing.getStatus() != ListingStatus.ACTIVE) {
            throw new IllegalStateException("Listing is not available: " + listing.getStatus());
        }
        if (listing.getSellerId().equals(buyerId)) {
            throw new IllegalArgumentException("Seller cannot buy their own listing");
        }

        // Early guard: reject if ticket-service explicitly reports event closed.
        if (!eventValidationClient.isEventOpenForSales(listing.getEventId())) {
            throw new IllegalStateException(
                    "Event is not open for sales: " + listing.getEventId());
        }

        String orderId = UUID.randomUUID().toString();
        String sagaId  = UUID.randomUUID().toString();

        listing.setStatus(ListingStatus.SOLD);
        listing.setPurchasedByUserId(buyerId);
        listing.setPurchasedOrderId(orderId);
        listingRepository.save(listing);

        redisTemplate.delete(L2_PREFIX + listing.getEventId());

        eventPublisher.publishOrderCreated(new OrderCreatedEvent(
                traceId, sagaId, orderId, buyerId, listing.getTicketId(), listing.getAskPrice()));

        log.info("Secondary purchase: listingId={} buyer={} orderId={}", id, buyerId, orderId);
        return listingMapper.toResponse(listing);
    }

    private Listing findOrThrow(String id) {
        return listingRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Listing not found: " + id));
    }
}
