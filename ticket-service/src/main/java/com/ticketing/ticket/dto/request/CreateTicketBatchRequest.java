package com.ticketing.ticket.dto.request;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Request DTO for bulk ticket creation via a seat-range generator.
 *
 * <p>The service expands (rowStart..rowEnd) × (seatStart..seatEnd) into individual
 * tickets and skips any seat that already exists in the database.
 *
 * <p>Row ranges can be either alphabetical ("A"–"D") or numeric ("1"–"5").
 * Leave rowStart/rowEnd null/blank to create tickets with no row dimension.
 *
 * <p>Example: section=VIP, rowStart=A, rowEnd=C, seatStart=1, seatEnd=10
 * → 30 tickets: A1–A10, B1–B10, C1–C10 (minus any already present).
 */
@Getter @Setter
public class CreateTicketBatchRequest {

    @NotBlank
    private String eventId;

    @NotBlank
    private String eventName;

    /** Optional — leave blank for general-admission or unnumbered sections. */
    private String section;

    /**
     * First row in the range (inclusive).
     * Single letter ("A") or numeric string ("1").
     * Must be provided together with {@code rowEnd}.
     */
    @Size(max = 10)
    private String rowStart;

    /**
     * Last row in the range (inclusive).
     * Single letter ("D") or numeric string ("5").
     * Must be provided together with {@code rowStart}.
     */
    @Size(max = 10)
    private String rowEnd;

    /** First seat number in the range (inclusive, ≥ 1). */
    @NotNull
    @Min(1)
    private Integer seatStart;

    /** Last seat number in the range (inclusive, ≥ seatStart). */
    @NotNull
    @Min(1)
    private Integer seatEnd;

    @NotNull
    @DecimalMin("0.01")
    @Digits(integer = 8, fraction = 2)
    private BigDecimal facePrice;

    // ── Cross-field validation helpers ───────────────────────────────────────

    @AssertTrue(message = "seatEnd must be >= seatStart")
    public boolean isSeatRangeValid() {
        return seatStart == null || seatEnd == null || seatEnd >= seatStart;
    }

    @AssertTrue(message = "rowStart and rowEnd must both be provided or both be empty")
    public boolean isRowRangeConsistent() {
        boolean hasStart = rowStart != null && !rowStart.isBlank();
        boolean hasEnd   = rowEnd   != null && !rowEnd.isBlank();
        return hasStart == hasEnd;   // both present or both absent
    }

    @AssertTrue(message = "Batch would generate more than 2000 tickets; split into smaller ranges")
    public boolean isBatchSizeWithinLimit() {
        if (seatStart == null || seatEnd == null || seatEnd < seatStart) return true; // other validator catches this
        int seatCount = seatEnd - seatStart + 1;

        boolean hasRowRange = rowStart != null && !rowStart.isBlank()
                           && rowEnd   != null && !rowEnd.isBlank();
        if (!hasRowRange) return seatCount <= 2000;

        String rs = rowStart.trim();
        String re = rowEnd.trim();
        int rowCount;
        if (rs.length() == 1 && Character.isLetter(rs.charAt(0))
         && re.length() == 1 && Character.isLetter(re.charAt(0))) {
            rowCount = Math.abs(Character.toUpperCase(re.charAt(0))
                              - Character.toUpperCase(rs.charAt(0))) + 1;
        } else {
            try {
                rowCount = Math.abs(Integer.parseInt(re) - Integer.parseInt(rs)) + 1;
            } catch (NumberFormatException ignored) {
                return true; // isRowRangeConsistent will surface the format issue
            }
        }
        return (long) rowCount * seatCount <= 2000;
    }
}
