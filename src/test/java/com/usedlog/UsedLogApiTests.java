package com.usedlog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

/** End-to-end API tests: real security filter chain, real JPA + Flyway schema on H2. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UsedLogApiTests {

    private static final String PASSWORD = "correct-horse-9";

    @Autowired
    MockMvc mvc;

    /** The CSRF token, obtained the way the browser gets it: from the XSRF-TOKEN cookie. */
    private String xsrfToken;

    @BeforeEach
    void fetchCsrfToken() throws Exception {
        MvcResult result = mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized()).andReturn();
        Cookie cookie = result.getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).as("XSRF-TOKEN cookie on the first response").isNotNull();
        xsrfToken = cookie.getValue();
    }

    // ---------- auth ----------

    @Test
    void apiRequiresSignIn() throws Exception {
        mvc.perform(get("/api/households")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void writesWithoutCsrfTokenAreRejected() throws Exception {
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(uniqueEmail(), "Alex")))
                .andExpect(status().isForbidden());
    }

    @Test
    void signInChecksThePassword() throws Exception {
        String email = uniqueEmail();
        register(email, "Alex");

        mvc.perform(post("/api/auth/login").with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "wrong-password"}
                                """.formatted(email)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Email or password is incorrect."));

        MvcResult ok = mvc.perform(post("/api/auth/login").with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s"}
                                """.formatted(email.toUpperCase(), PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Alex"))
                .andReturn();

        MockHttpSession session = (MockHttpSession) ok.getRequest().getSession(false);
        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email));
    }

    @Test
    void duplicateEmailIsAConflict() throws Exception {
        String email = uniqueEmail();
        register(email, "Alex");
        mvc.perform(post("/api/auth/register").with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(email, "Alex again")))
                .andExpect(status().isConflict());
    }

    // ---------- the main flow ----------

    @Test
    void logEntriesSeeTotalsAndRemoveOne() throws Exception {
        MockHttpSession alex = register(uniqueEmail(), "Alex");
        String householdId = JsonPath.read(createHousehold(alex, "Home").getResponse().getContentAsString(), "$.id");

        addEntry(alex, householdId, "Paper towels", "2", "roll", "Kitchen", null);
        addEntry(alex, householdId, "  paper   towels ", "1", "roll", "Kitchen", null);
        MvcResult milk = addEntry(alex, householdId, "Milk", "1", "carton", "Pantry", null);
        String milkId = JsonPath.read(milk.getResponse().getContentAsString(), "$.id");

        String month = YearMonth.now(ZoneOffset.UTC).toString();

        mvc.perform(get("/api/households/{id}/entries", householdId).session(alex)
                        .param("month", month).param("tz", "UTC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].itemName").value("Milk"))
                .andExpect(jsonPath("$[1].itemName").value("paper towels"));

        mvc.perform(get("/api/households/{id}/totals", householdId).session(alex)
                        .param("month", month).param("tz", "UTC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].itemKey").value("paper towels"))
                .andExpect(jsonPath("$[0].quantity").value(3.0))
                .andExpect(jsonPath("$[0].times").value(2))
                .andExpect(jsonPath("$[1].itemKey").value("milk"));

        // One-tap list: most-used first, carrying the amount from the latest entry (1 roll, not 2).
        mvc.perform(get("/api/households/{id}/items/frequent", householdId).session(alex))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].itemName").value("paper towels"))
                .andExpect(jsonPath("$[0].quantity").value(1.0))
                .andExpect(jsonPath("$[0].timesUsed").value(2));

        mvc.perform(delete("/api/households/{id}/entries/{entryId}", householdId, milkId)
                        .session(alex).with(xsrf()))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/households/{id}/entries", householdId).session(alex)
                        .param("month", month).param("tz", "UTC"))
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void inviteCodeSharesTheLogAndOutsidersSeeNothing() throws Exception {
        MockHttpSession alex = register(uniqueEmail(), "Alex");
        MvcResult created = createHousehold(alex, "Office");
        String householdId = JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        String code = JsonPath.read(created.getResponse().getContentAsString(), "$.inviteCode");
        addEntry(alex, householdId, "Printer paper", "1", "pack", "Office", null);

        // Codes are accepted the way people paste them: lower case, with a dash.
        String pasted = (code.substring(0, 4) + "-" + code.substring(4)).toLowerCase();
        MockHttpSession sam = register(uniqueEmail(), "Sam");
        mvc.perform(post("/api/households/join").session(sam).with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"inviteCode": "%s"}
                                """.formatted(pasted)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members", hasSize(2)))
                .andExpect(jsonPath("$.members[1].displayName").value("Sam"));

        String month = YearMonth.now(ZoneOffset.UTC).toString();
        mvc.perform(get("/api/households/{id}/entries", householdId).session(sam)
                        .param("month", month).param("tz", "UTC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        MockHttpSession outsider = register(uniqueEmail(), "Pat");
        mvc.perform(get("/api/households/{id}/entries", householdId).session(outsider)
                        .param("month", month).param("tz", "UTC"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/households/{id}/entries", householdId).session(outsider).with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(entryBody("Toner", "1", "box", "Office", null)))
                .andExpect(status().isNotFound());
    }

    // ---------- validation ----------

    @Test
    void rejectsBadInput() throws Exception {
        MockHttpSession alex = register(uniqueEmail(), "Alex");
        String householdId = JsonPath.read(createHousehold(alex, "Home").getResponse().getContentAsString(), "$.id");

        mvc.perform(post("/api/households/{id}/entries", householdId).session(alex).with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(entryBody("Milk", "0", "carton", "Pantry", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("quantity")));

        String tomorrow = Instant.now().plus(1, ChronoUnit.DAYS).toString();
        mvc.perform(post("/api/households/{id}/entries", householdId).session(alex).with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(entryBody("Milk", "1", "carton", "Pantry", tomorrow)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("usedAt can't be in the future."));

        mvc.perform(get("/api/households/{id}/entries", householdId).session(alex)
                        .param("month", "October").param("tz", "UTC"))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/households/{id}/entries", householdId).session(alex)
                        .param("month", "2026-10").param("tz", "Mars/Olympus"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void monthUsesTheViewersTimeZone() throws Exception {
        MockHttpSession alex = register(uniqueEmail(), "Alex");
        String householdId = JsonPath.read(createHousehold(alex, "Home").getResponse().getContentAsString(), "$.id");

        // 03:00 UTC on Oct 1 is still Sep 30 in New York.
        addEntry(alex, householdId, "Coffee pods", "2", "pod", "Pantry", "2025-10-01T03:00:00Z");

        mvc.perform(get("/api/households/{id}/entries", householdId).session(alex)
                        .param("month", "2025-09").param("tz", "America/New_York"))
                .andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get("/api/households/{id}/entries", householdId).session(alex)
                        .param("month", "2025-10").param("tz", "America/New_York"))
                .andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/households/{id}/entries", householdId).session(alex)
                        .param("month", "2025-10").param("tz", "UTC"))
                .andExpect(jsonPath("$", hasSize(1)));
    }

    // ---------- helpers ----------

    /** Sends the CSRF token like Angular does: the XSRF-TOKEN cookie plus the same value in X-XSRF-TOKEN. */
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

    private MockHttpSession register(String email, String displayName) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/register").with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(email, displayName)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email))
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private MvcResult createHousehold(MockHttpSession session, String name) throws Exception {
        return mvc.perform(post("/api/households").session(session).with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s"}
                                """.formatted(name)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.inviteCode").isNotEmpty())
                .andReturn();
    }

    private MvcResult addEntry(MockHttpSession session, String householdId, String item, String qty,
                               String unit, String category, String usedAt) throws Exception {
        return mvc.perform(post("/api/households/{id}/entries", householdId).session(session).with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(entryBody(item, qty, unit, category, usedAt)))
                .andExpect(status().isCreated())
                .andReturn();
    }

    private static String registerBody(String email, String displayName) {
        return """
                {"email": "%s", "password": "%s", "displayName": "%s"}
                """.formatted(email, PASSWORD, displayName);
    }

    private static String entryBody(String item, String qty, String unit, String category, String usedAt) {
        String when = usedAt == null ? "" : ", \"usedAt\": \"" + usedAt + "\"";
        return """
                {"itemName": "%s", "quantity": %s, "unit": "%s", "category": "%s"%s}
                """.formatted(item, qty, unit, category, when);
    }

    private static String uniqueEmail() {
        return "person-" + UUID.randomUUID() + "@example.com";
    }
}
