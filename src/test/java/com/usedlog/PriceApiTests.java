package com.usedlog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;
import com.usedlog.price.ReceiptReader;
import com.usedlog.price.ReceiptReader.DraftItem;
import com.usedlog.price.ReceiptReader.ReceiptDraft;

/** Receipts and prices through the real API; only the call to Claude is replaced. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PriceApiTests {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ReceiptReader reader;

    private String xsrfToken;

    @BeforeEach
    void fetchCsrfToken() throws Exception {
        MvcResult result = mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized()).andReturn();
        Cookie cookie = result.getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        xsrfToken = cookie.getValue();
    }

    @Test
    void receiptsBuildPriceHistoryChangesAndComparisons() throws Exception {
        MockHttpSession alex = register("Alex");
        String hid = createHousehold(alex);
        Instant tenDaysAgo = Instant.now().minus(10, ChronoUnit.DAYS);
        Instant twoDaysAgo = Instant.now().minus(2, ChronoUnit.DAYS);

        // First trip, no total given: total = lines + tax.
        mvc.perform(post("/api/households/{id}/receipts", hid).session(alex).with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"storeName": "Aldi", "purchasedAt": "%s", "tax": 0.20, "items": [
                                  {"name": "Eggs, 12 ct", "receiptText": "LG EGGS 12CT", "quantity": 1, "unit": "pcs",
                                   "unitPrice": 2.79, "lineTotal": 2.79, "category": "Dairy & eggs"},
                                  {"name": "Bananas", "quantity": 2.0, "unit": "lb", "unitPrice": 0.59, "lineTotal": 1.18,
                                   "category": "Produce"}
                                ]}
                                """.formatted(tenDaysAgo)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.storeName").value("Aldi"))
                .andExpect(jsonPath("$.total").value(4.17))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].receiptText").value("LG EGGS 12CT"))
                .andExpect(jsonPath("$.priceChanges", hasSize(0)));

        // Second trip at another store: eggs went up 25.1%, bananas didn't move.
        MvcResult second = mvc.perform(post("/api/households/{id}/receipts", hid).session(alex).with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"storeName": " giant ", "purchasedAt": "%s", "total": 6.00, "items": [
                                  {"name": "eggs, 12 CT", "quantity": 1, "unit": "pcs", "unitPrice": 3.49, "lineTotal": 3.49,
                                   "category": "Dairy & eggs"},
                                  {"name": "Bananas", "quantity": 1.5, "unit": "lb", "unitPrice": 0.59, "lineTotal": 0.89,
                                   "category": "Produce"}
                                ]}
                                """.formatted(twoDaysAgo)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.total").value(6.0))
                .andExpect(jsonPath("$.priceChanges", hasSize(1)))
                .andExpect(jsonPath("$.priceChanges[0].previousPrice").value(2.79))
                .andExpect(jsonPath("$.priceChanges[0].previousStore").value("Aldi"))
                .andExpect(jsonPath("$.priceChanges[0].latestPrice").value(3.49))
                .andExpect(jsonPath("$.priceChanges[0].changePercent").value(25.1))
                .andReturn();
        String secondId = JsonPath.read(second.getResponse().getContentAsString(), "$.id");

        mvc.perform(get("/api/households/{id}/prices", hid).session(alex))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changes", hasSize(1)))
                .andExpect(jsonPath("$.changes[0].itemKey").value("eggs, 12 ct"))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].itemName").value("Bananas"))
                .andExpect(jsonPath("$.items[1].latestPrice").value(3.49))
                .andExpect(jsonPath("$.items[1].lowestPrice").value(2.79))
                .andExpect(jsonPath("$.items[1].lowestStore").value("Aldi"))
                .andExpect(jsonPath("$.items[1].purchases").value(2))
                .andExpect(jsonPath("$.items[1].storeCount").value(2));

        mvc.perform(get("/api/households/{id}/prices/history", hid).session(alex).param("item", "Eggs, 12 ct"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.unit").value("pcs"))
                .andExpect(jsonPath("$.points", hasSize(2)))
                .andExpect(jsonPath("$.points[0].storeName").value("Aldi"))
                .andExpect(jsonPath("$.stores[0].storeName").value("Aldi"))
                .andExpect(jsonPath("$.stores[1].storeName").value("giant"));

        mvc.perform(get("/api/households/{id}/prices/compare", hid).session(alex))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].itemKey").value("eggs, 12 ct"))
                .andExpect(jsonPath("$[0].savingsPercent").value(20.1))
                .andExpect(jsonPath("$[0].stores[0].storeName").value("Aldi"));

        mvc.perform(get("/api/households/{id}/stores", hid).session(alex))
                .andExpect(jsonPath("$", hasSize(2)));

        mvc.perform(delete("/api/households/{id}/receipts/{rid}", hid, secondId).session(alex).with(xsrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/households/{id}/receipts/{rid}", hid, secondId).session(alex))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/households/{id}/prices", hid).session(alex))
                .andExpect(jsonPath("$.changes", hasSize(0)))
                .andExpect(jsonPath("$.items[1].purchases").value(1));
    }

    @Test
    void spendingAndReceiptListsUseTheViewersMonth() throws Exception {
        MockHttpSession alex = register("Alex");
        String hid = createHousehold(alex);

        // 04:30 UTC on Feb 1 is still Jan 31 in New York.
        saveSimpleReceipt(alex, hid, "Costco", "2025-02-01T04:30:00Z", "100.00", "8.00");
        saveSimpleReceipt(alex, hid, "Giant", "2025-02-10T15:00:00Z", "20.00", null);

        mvc.perform(get("/api/households/{id}/spending", hid).session(alex)
                        .param("year", "2025").param("tz", "America/New_York"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(128.0))
                .andExpect(jsonPath("$.months", hasSize(12)))
                .andExpect(jsonPath("$.months[0].month").value("2025-01"))
                .andExpect(jsonPath("$.months[0].total").value(108.0))
                .andExpect(jsonPath("$.months[0].byStore[0].name").value("Costco"))
                .andExpect(jsonPath("$.months[0].byCategory[0].name").value("Pantry"))
                .andExpect(jsonPath("$.months[0].byCategory[1].name").value("Tax"))
                .andExpect(jsonPath("$.months[1].total").value(20.0))
                .andExpect(jsonPath("$.months[1].receipts").value(1));

        mvc.perform(get("/api/households/{id}/receipts", hid).session(alex)
                        .param("month", "2025-01").param("tz", "America/New_York"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].storeName").value("Costco"))
                .andExpect(jsonPath("$[0].itemCount").value(1));
    }

    @Test
    void readerStatusAndLearnedNames() throws Exception {
        MockHttpSession alex = register("Alex");
        String hid = createHousehold(alex);

        mvc.perform(get("/api/households/{id}/receipts/reader", hid).session(alex))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claude").value(false));
        when(reader.isConfigured()).thenReturn(true);
        mvc.perform(get("/api/households/{id}/receipts/reader", hid).session(alex))
                .andExpect(jsonPath("$.claude").value(true));

        // The same printed line saved twice: the newer name wins, matched regardless of case and spacing.
        saveLine(alex, hid, "2026-01-05T12:00:00Z", "GV 2% MLK GAL", "Milk", "Dairy & eggs");
        saveLine(alex, hid, "2026-02-05T12:00:00Z", "gv 2%  mlk gal", "Milk 2%, 1 gal", "Dairy & eggs");
        saveLine(alex, hid, "2026-02-05T12:00:00Z", "BANANAS", "Bananas", "Produce");

        mvc.perform(get("/api/households/{id}/receipts/aliases", hid).session(alex))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                // (jsonPath expressions are format strings, so match on category rather than a name with "%")
                .andExpect(jsonPath("$[?(@.category == 'Dairy & eggs')].name").value("Milk 2%, 1 gal"))
                .andExpect(jsonPath("$[?(@.category == 'Dairy & eggs')].receiptText").value("gv 2% mlk gal"))
                .andExpect(jsonPath("$[?(@.name == 'Bananas')].category").value("Produce"));

        MockHttpSession pat = register("Pat");
        mvc.perform(get("/api/households/{id}/receipts/aliases", hid).session(pat)).andExpect(status().isNotFound());
    }

    private void saveLine(MockHttpSession session, String hid, String at, String receiptText, String name,
                          String category) throws Exception {
        mvc.perform(post("/api/households/{id}/receipts", hid).session(session).with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"storeName": "Giant", "purchasedAt": "%s", "items": [
                                  {"name": "%s", "receiptText": "%s", "quantity": 1, "unit": "pcs",
                                   "unitPrice": 3.98, "lineTotal": 3.98, "category": "%s"}
                                ]}
                                """.formatted(at, name, receiptText, category)))
                .andExpect(status().isCreated());
    }

    @Test
    void readingPhotosNeedsAnApiKey() throws Exception {
        MockHttpSession alex = register("Alex");
        String hid = createHousehold(alex);
        mvc.perform(multipart("/api/households/{id}/receipts/read", hid).file(photo("image/jpeg"))
                        .session(alex).with(xsrf()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail", containsString("isn't set up")));
    }

    @Test
    void readingPhotosReturnsADraftAndSavesNothing() throws Exception {
        MockHttpSession alex = register("Alex");
        String hid = createHousehold(alex);
        when(reader.isConfigured()).thenReturn(true);
        when(reader.read(anyList(), anyList(), anyList())).thenReturn(new ReceiptDraft(
                "Giant", "2026-10-20", "14:05", "USD",
                List.of(new DraftItem("GNT LG EGGS 12CT", "Eggs, large, 12 ct", BigDecimal.ONE, "pcs",
                        new BigDecimal("3.49"), new BigDecimal("3.49"), "Dairy & eggs")),
                new BigDecimal("3.49"), new BigDecimal("0.21"), new BigDecimal("3.70"), null));

        mvc.perform(multipart("/api/households/{id}/receipts/read", hid)
                        .file(photo("image/jpeg")).file(photo("image/png"))
                        .session(alex).with(xsrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeName").value("Giant"))
                .andExpect(jsonPath("$.items[0].name").value("Eggs, large, 12 ct"))
                .andExpect(jsonPath("$.total").value(3.7));

        verify(reader).read(argThat(photos -> photos.size() == 2
                && photos.get(0).mediaType().equals("image/jpeg")
                && photos.get(1).mediaType().equals("image/png")), anyList(), anyList());

        mvc.perform(get("/api/households/{id}/prices", hid).session(alex))
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void rejectsBadPhotosAndBadReceipts() throws Exception {
        MockHttpSession alex = register("Alex");
        String hid = createHousehold(alex);
        when(reader.isConfigured()).thenReturn(true);

        mvc.perform(multipart("/api/households/{id}/receipts/read", hid).file(photo("text/plain"))
                        .session(alex).with(xsrf()))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(multipart("/api/households/{id}/receipts/read", hid).session(alex).with(xsrf()))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/households/{id}/receipts", hid).session(alex).with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"storeName": "Aldi", "purchasedAt": "2026-01-05T12:00:00Z", "items": []}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("items")));
    }

    @Test
    void outsidersCannotReadOrAddReceipts() throws Exception {
        MockHttpSession alex = register("Alex");
        String hid = createHousehold(alex);
        saveSimpleReceipt(alex, hid, "Aldi", "2026-01-05T12:00:00Z", "5.00", null);

        MockHttpSession pat = register("Pat");
        mvc.perform(get("/api/households/{id}/prices", hid).session(pat)).andExpect(status().isNotFound());
        mvc.perform(get("/api/households/{id}/receipts", hid).session(pat).param("month", "2026-01"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/households/{id}/receipts", hid).session(pat).with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(simpleReceipt("Aldi", "2026-01-05T12:00:00Z", "5.00", null)))
                .andExpect(status().isNotFound());
    }

    // ---------- helpers ----------

    private void saveSimpleReceipt(MockHttpSession session, String hid, String store, String at, String amount,
                                   String tax) throws Exception {
        mvc.perform(post("/api/households/{id}/receipts", hid).session(session).with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(simpleReceipt(store, at, amount, tax)))
                .andExpect(status().isCreated());
    }

    private static String simpleReceipt(String store, String at, String amount, String tax) {
        return """
                {"storeName": "%s", "purchasedAt": "%s", "tax": %s, "items": [
                  {"name": "Rice, 20 lb", "quantity": 1, "unit": "pcs", "unitPrice": %s, "lineTotal": %s,
                   "category": "Pantry"}
                ]}
                """.formatted(store, at, tax == null ? "null" : tax, amount, amount);
    }

    private static MockMultipartFile photo(String type) {
        return new MockMultipartFile("photos", "receipt", type, new byte[] {1, 2, 3, 4});
    }

    private RequestPostProcessor xsrf() {
        return request -> {
            Cookie[] existing = request.getCookies();
            Cookie[] cookies = existing == null ? new Cookie[1] : Arrays.copyOf(existing, existing.length + 1);
            cookies[cookies.length - 1] = new Cookie("XSRF-TOKEN", xsrfToken);
            request.setCookies(cookies);
            request.addHeader("X-XSRF-TOKEN", xsrfToken);
            return request;
        };
    }

    private MockHttpSession register(String name) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/register").with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "correct-horse-9", "displayName": "%s"}
                                """.formatted("person-" + UUID.randomUUID() + "@example.com", name)))
                .andExpect(status().isCreated())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private String createHousehold(MockHttpSession session) throws Exception {
        MvcResult result = mvc.perform(post("/api/households").session(session).with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Home"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }
}
