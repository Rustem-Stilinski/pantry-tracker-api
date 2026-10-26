package com.usedlog.entry;

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
record MonthRange(Instant from, Instant to) {

    static MonthRange of(String month, String timeZone) {
        YearMonth yearMonth;
        try {
            yearMonth = YearMonth.parse(month);
        } catch (DateTimeParseException | NullPointerException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "month must look like 2026-10.");
        }
        ZoneId zone;
        try {
            zone = (timeZone == null || timeZone.isBlank()) ? ZoneId.of("UTC") : ZoneId.of(timeZone);
        } catch (DateTimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "tz must be an IANA time zone, such as America/New_York.");
        }
        Instant from = yearMonth.atDay(1).atStartOfDay(zone).toInstant();
        Instant to = yearMonth.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
        return new MonthRange(from, to);
    }
}
