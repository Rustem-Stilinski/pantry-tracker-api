package com.usedlog.price;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.usedlog.common.MonthRange;
import com.usedlog.price.PriceDtos.ReaderStatus;
import com.usedlog.price.PriceDtos.ReceiptAlias;
import com.usedlog.price.PriceDtos.ReceiptDetail;
import com.usedlog.price.PriceDtos.ReceiptSummary;
import com.usedlog.price.PriceDtos.SaveReceiptRequest;
import com.usedlog.price.ReceiptReader.ReceiptDraft;
import com.usedlog.security.AppUserPrincipal;

/**
 * Receipts for one household. Adding one is two steps: POST /read with photos returns a draft
 * (nothing saved), the person checks it, then POST / saves it.
 */
@RestController
@RequestMapping("/api/households/{householdId}/receipts")
public class ReceiptController {

    private final ReceiptService receipts;

    public ReceiptController(ReceiptService receipts) {
        this.receipts = receipts;
    }

    /** Whether photos can be read on the server (Claude); otherwise the app reads them on the device. */
    @GetMapping("/reader")
    public ReaderStatus reader(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable UUID householdId) {
        return receipts.readerStatus(householdId, me.getId());
    }

    /** Learned names: what each printed receipt line was last saved as. */
    @GetMapping("/aliases")
    public List<ReceiptAlias> aliases(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable UUID householdId) {
        return receipts.aliases(householdId, me.getId());
    }

    /** Multipart form with one to four parts named "photos", top of the receipt first. */
    @PostMapping(path = "/read", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ReceiptDraft read(@AuthenticationPrincipal AppUserPrincipal me,
                             @PathVariable UUID householdId,
                             @RequestPart(name = "photos", required = false) List<MultipartFile> photos) {
        return receipts.read(householdId, me.getId(), photos);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReceiptDetail save(@AuthenticationPrincipal AppUserPrincipal me,
                              @PathVariable UUID householdId,
                              @Valid @RequestBody SaveReceiptRequest body) {
        return receipts.save(householdId, me.getId(), body);
    }

    @GetMapping
    public List<ReceiptSummary> list(@AuthenticationPrincipal AppUserPrincipal me,
                                     @PathVariable UUID householdId,
                                     @RequestParam String month,
                                     @RequestParam(required = false) String tz) {
        return receipts.list(householdId, me.getId(), MonthRange.of(month, tz));
    }

    @GetMapping("/{receiptId}")
    public ReceiptDetail get(@AuthenticationPrincipal AppUserPrincipal me,
                             @PathVariable UUID householdId,
                             @PathVariable UUID receiptId) {
        return receipts.get(householdId, me.getId(), receiptId);
    }

    @DeleteMapping("/{receiptId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AppUserPrincipal me,
                       @PathVariable UUID householdId,
                       @PathVariable UUID receiptId) {
        receipts.delete(householdId, me.getId(), receiptId);
    }
}
