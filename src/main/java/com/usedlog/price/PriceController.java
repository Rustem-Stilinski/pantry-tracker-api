package com.usedlog.price;

import java.time.Year;
import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.usedlog.common.MonthRange;
import com.usedlog.price.PriceDtos.ItemHistory;
import com.usedlog.price.PriceDtos.PricesOverview;
import com.usedlog.price.PriceDtos.StoreComparison;
import com.usedlog.price.PriceDtos.YearSpending;
import com.usedlog.security.AppUserPrincipal;

@RestController
@RequestMapping("/api/households/{householdId}")
public class PriceController {

    private final PriceService prices;

    public PriceController(PriceService prices) {
        this.prices = prices;
    }

    /** Every item bought in the last year with its latest, lowest and average price, plus recent big changes. */
    @GetMapping("/prices")
    public PricesOverview overview(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable UUID householdId) {
        return prices.overview(householdId, me.getId());
    }

    /** One item's purchases over time. {@code unit} is optional; the most common unit is used when missing. */
    @GetMapping("/prices/history")
    public ItemHistory history(@AuthenticationPrincipal AppUserPrincipal me,
                               @PathVariable UUID householdId,
                               @RequestParam String item,
                               @RequestParam(required = false) String unit) {
        return prices.history(householdId, me.getId(), item, unit);
    }

    /** Items bought at two or more stores in the last six months, biggest savings first. */
    @GetMapping("/prices/compare")
    public List<StoreComparison> compare(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable UUID householdId) {
        return prices.compare(householdId, me.getId());
    }

    /** Spending per month for a year, in the viewer's time zone. */
    @GetMapping("/spending")
    public YearSpending spending(@AuthenticationPrincipal AppUserPrincipal me,
                                 @PathVariable UUID householdId,
                                 @RequestParam(required = false) Integer year,
                                 @RequestParam(required = false) String tz) {
        int chosen = year != null ? year : Year.now(MonthRange.zone(tz)).getValue();
        return prices.spending(householdId, me.getId(), chosen, tz);
    }

    /** Store names this household has receipts from, for autocomplete. */
    @GetMapping("/stores")
    public List<String> stores(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable UUID householdId) {
        return prices.stores(householdId, me.getId());
    }
}
