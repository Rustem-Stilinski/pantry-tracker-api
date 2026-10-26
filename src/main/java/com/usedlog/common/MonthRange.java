package com.usedlog.common;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * A calendar month in the viewer's time zone, as a half-open instant range [from, to).
 * "October 2026" in New York starts at 2026-10-01T04:00Z, not midnight UTC.
 */
public record MonthRange(Instant from, Instant to) {

    public static MonthRange of(String month, String timeZone) {
        YearMonth yearMonth;
        try {
            yearMonth = YearMonth.parse(month);
        } catch (DateTimeParseException | NullPointerException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "month must look like 2026-10.");
        }
        return of(yearMonth, zone(timeZone));
    }

    public static MonthRange of(YearMonth yearMonth, ZoneId zone) {
        Instant from = yearMonth.atDay(1).atStartOfDay(zone).toInstant();
        Instant to = yearMonth.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
        return new MonthRange(from, to);
    }

    /** IANA zone from a request parameter; UTC when missing; 400 when invalid. */
    public static ZoneId zone(String timeZone) {
        try {
            return (timeZone == null || timeZone.isBlank()) ? ZoneId.of("UTC") : ZoneId.of(timeZone);
        } catch (DateTimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "tz must be an IANA time zone, such as America/New_York.");
        }
    }
}
