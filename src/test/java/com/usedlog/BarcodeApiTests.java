package com.usedlog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;
import com.usedlog.barcode.ProductLookup;
import com.usedlog.barcode.ProductLookup.Product;

/** Scanning barcodes through the real API; only the public product lookup is replaced. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BarcodeApiTests {

    private static final String MILK_UPC_A = "036000291452";
    private static final String MILK_EAN_13 = "0036000291452";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ProductLookup productLookup;

    private String xsrfToken;

    @BeforeEach
    void fetchCsrfToken() throws Exception {
        MvcResult result = mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized()).andReturn();
        Cookie cookie = result.getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        xsrfToken = cookie.getValue();
    }

    @Test
    void aNewBarcodeComesBackWithASuggestedName() throws Exception {
        MockHttpSession alex = register("Alex");
        String hid = createHousehold(alex);
        when(productLookup.find(MILK_EAN_13)).thenReturn(Optional.of(
                new Product("Fairlife 2% Milk, 52 fl oz", "Fairlife", "52 fl oz", "Open Food Facts")));

        mvc.perform(get("/api/households/{id}/barcodes/{code}", hid, MILK_UPC_A).param("format", "upc_a").session(alex))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(MILK_EAN_13))
                .andExpect(jsonPath("$.known").value(false))
                .andExpect(jsonPath("$.itemName").isEmpty())
                .andExpect(jsonPath("$.suggestion.name").value("Fairlife 2% Milk, 52 fl oz"))
                .andExpect(jsonPath("$.suggestion.source").value("Open Food Facts"))
                .andExpect(jsonPath("$.prices").isEmpty());
    }

    @Test
    void aNamedBarcodeOpensTheItemWithItsReceiptPrices() throws Exception {
        MockHttpSession alex = register("Alex");
        String hid = createHousehold(alex);
        saveMilkReceipt(alex, hid, "Aldi", Instant.now().minus(9, ChronoUnit.DAYS), "4.89");
        saveMilkReceipt(alex, hid, "Wegmans", Instant.now().minus(2, ChronoUnit.DAYS), "5.49");

        // Named as typed: spacing and case don't matter, it matches the receipt's "Fairlife 2% milk".
        mvc.perform(put("/api/households/{id}/barcodes/{code}", hid, MILK_UPC_A).session(alex).with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemName": "  fairlife  2% MILK "}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(MILK_EAN_13))
                .andExpect(jsonPath("$.known").value(true))
                .andExpect(jsonPath("$.itemName").value("fairlife 2% MILK"))
                .andExpect(jsonPath("$.prices.itemName").value("Fairlife 2% milk"))
                .andExpect(jsonPath("$.prices.latestPrice").value(5.49))
                .andExpect(jsonPath("$.prices.latestStore").value("Wegmans"))
                .andExpect(jsonPath("$.prices.lowestPrice").value(4.89))
                .andExpect(jsonPath("$.prices.lowestStore").value("Aldi"))
                .andExpect(jsonPath("$.prices.purchases").value(2));

        // Scanned again, read as EAN-13 this time: same barcode, and no outside lookup for a known one.
        mvc.perform(get("/api/households/{id}/barcodes/{code}", hid, MILK_EAN_13).session(alex))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.known").value(true))
                .andExpect(jsonPath("$.suggestion").isEmpty())
                .andExpect(jsonPath("$.prices.latestPrice").value(5.49));
        verify(productLookup, never()).find(anyString());

        // Renamed to something never bought: still known, no prices.
        mvc.perform(put("/api/households/{id}/barcodes/{code}", hid, MILK_EAN_13).session(alex).with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemName": "Oat milk"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemName").value("Oat milk"))
                .andExpect(jsonPath("$.prices").isEmpty());
        mvc.perform(get("/api/households/{id}/barcodes/{code}", hid, MILK_UPC_A).session(alex))
                .andExpect(jsonPath("$.itemName").value("Oat milk"));
    }

    @Test
    void barcodesBelongToOneHousehold() throws Exception {
        MockHttpSession alex = register("Alex");
        String hid = createHousehold(alex);
        name(alex, hid, MILK_EAN_13, "Fairlife 2% milk");

        MockHttpSession pat = register("Pat");
        String patsHid = createHousehold(pat);
        mvc.perform(get("/api/households/{id}/barcodes/{code}", hid, MILK_EAN_13).session(pat))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/households/{id}/barcodes/{code}", patsHid, MILK_EAN_13).session(pat))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.known").value(false));
    }

    @Test
    void misreadCodesAndBadNamesAreRejected() throws Exception {
        MockHttpSession alex = register("Alex");
        String hid = createHousehold(alex);

        mvc.perform(get("/api/households/{id}/barcodes/{code}", hid, "036000291453").session(alex))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("isn't a product barcode")));
        mvc.perform(get("/api/households/{id}/barcodes/{code}", hid, "hello").session(alex))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/households/{id}/barcodes/{code}", hid, MILK_EAN_13).session(alex).with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemName": "  "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("itemName")));

        // Without the CSRF header a write is refused.
        mvc.perform(put("/api/households/{id}/barcodes/{code}", hid, MILK_EAN_13).session(alex)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemName": "Milk"}
                                """))
                .andExpect(status().isForbidden());

        // Signed out: 401.
        mvc.perform(get("/api/households/{id}/barcodes/{code}", hid, MILK_EAN_13))
                .andExpect(status().isUnauthorized());
    }

    // ---------- helpers ----------

    private void name(MockHttpSession session, String hid, String code, String itemName) throws Exception {
        mvc.perform(put("/api/households/{id}/barcodes/{code}", hid, code).session(session).with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemName": "%s"}
                                """.formatted(itemName)))
                .andExpect(status().isOk());
    }

    private void saveMilkReceipt(MockHttpSession session, String hid, String store, Instant at, String price)
            throws Exception {
        mvc.perform(post("/api/households/{id}/receipts", hid).session(session).with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"storeName": "%s", "purchasedAt": "%s", "items": [
                                  {"name": "Fairlife 2%% milk", "quantity": 1, "unit": "pcs", "unitPrice": %s,
                                   "lineTotal": %s, "category": "Dairy & eggs"}
                                ]}
                                """.formatted(store, at, price, price)))
                .andExpect(status().isCreated());
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
