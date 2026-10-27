package com.usedlog.price;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads receipt photos with the Claude Messages API.
 * The reply is constrained to receipt-schema.json with structured outputs, so it always parses.
 *
 * Configuration (application.yml / environment):
 *   ANTHROPIC_API_KEY        required to turn the feature on
 *   ANTHROPIC_WORKSPACE_ID   only for a key that isn't scoped to one workspace
 *   ANTHROPIC_MODEL          default claude-haiku-5-5 (fast and inexpensive, built for extraction)
 */
@Component
public class AnthropicReceiptReader implements ReceiptReader {

    private static final Logger log = LoggerFactory.getLogger(AnthropicReceiptReader.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String API_VERSION = "2023-06-01";
    private static final int MAX_TOKENS = 16_000;

    private static final String SYSTEM_PROMPT = """
            You read photos of shopping receipts and turn them into data for a household's price tracker.

            Rules:
            - One entry in items per purchased line. Skip lines that are not products: subtotal, tax, total,
              payment, change, savings summaries, loyalty points, barcodes.
            - name: the short product name a person would use, written out instead of receipt abbreviations,
              with the size or count when it is printed. "GV 2% MLK GAL" becomes "Milk 2%, 1 gal";
              "LG AA EGGS 12CT" becomes "Eggs, large, 12 ct". If the product is one of the household's
              existing items listed in the message, use that exact name.
            - receiptText: the line exactly as printed.
            - quantity and unit: for items sold by weight use the weight and its unit (2.31 lb). Otherwise
              use the count and "pcs" (3 pcs when the line says 3 @ 1.29). Use another unit such as "gal"
              only when the receipt states it.
            - unitPrice: the price of one unit after any discount on that line. lineTotal: what was paid for
              the line after those discounts. When a discount or coupon line follows an item, subtract it
              from that item instead of listing it. Fees and deposits (bag fee, bottle deposit) are items
              in category Other.
            - category: the closest one from the allowed list.
            - storeName: the store's everyday name, such as "Giant" or "Trader Joe's", not an address or a
              legal company name. If it matches one of the household's existing stores, use that exact name.
            - storeLocation: the branch's address as printed under the name, on one line with normal
              capitalization, such as "11620 Monument Dr, Fairfax, VA 22030"; null when none is printed.
            - purchaseDate (YYYY-MM-DD) and purchaseTime (24-hour HH:MM) as printed; null when missing.
            - subtotal, tax and total as printed; null when missing.
            - Numbers have no currency symbols. currency is the ISO code: USD unless the receipt shows another.
            - notes: one short sentence about anything the person should double-check, such as a cut-off
              receipt, an unreadable line, or items that don't add up to the total; null when all is clear.
            - If a photo is not a receipt, return no items and say so in notes.
            """;

    private final String apiKey;
    private final String workspaceId;
    private final String model;
    private final RestClient http;
    private final Map<String, Object> schema;

    public AnthropicReceiptReader(@Value("${app.receipts.anthropic-api-key:}") String apiKey,
                                  @Value("${app.receipts.workspace-id:}") String workspaceId,
                                  @Value("${app.receipts.model:claude-haiku-5-5}") String model,
                                  @Value("${app.receipts.api-url:https://api.anthropic.com}") String apiUrl) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.workspaceId = workspaceId == null ? "" : workspaceId.trim();
        this.model = model;

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        requestFactory.setReadTimeout(Duration.ofSeconds(120));
        this.http = RestClient.builder().baseUrl(apiUrl).requestFactory(requestFactory).build();
        this.schema = loadSchema();
    }

    @Override
    public boolean isConfigured() {
        return !apiKey.isEmpty();
    }

    @Override
    public ReceiptDraft read(List<Photo> photos, List<String> knownItems, List<String> knownStores) {
        String requestBody = JSON.writeValueAsString(requestBody(photos, knownItems, knownStores));

        String responseBody;
        try {
            responseBody = http.post()
                    .uri("/v1/messages")
                    .headers(headers -> {
                        headers.setBearerAuth(apiKey);
                        headers.set("anthropic-version", API_VERSION);
                        if (!workspaceId.isEmpty()) {
                            headers.set("anthropic-workspace-id", workspaceId);
                        }
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            log.warn("Claude API returned {} while reading a receipt: {}", status, abbreviate(ex.getResponseBodyAsString()));
            if (status == 400 && ex.getResponseBodyAsString().contains("anthropic-workspace-id")) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "The server's Claude API key isn't scoped to a workspace. Set ANTHROPIC_WORKSPACE_ID, "
                                + "or use a key created for one workspace. You can enter this receipt by hand meanwhile.");
            }
            if (status == 401 || status == 403) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "The server's Claude API key was rejected. Check ANTHROPIC_API_KEY, or enter this receipt by hand.");
            }
            if (status == 429 || status >= 500) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "The receipt reader is busy right now. Try again in a minute, or enter this receipt by hand.");
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "The receipt reader couldn't process these photos. Try a different photo, or enter this receipt by hand.");
        } catch (ResourceAccessException ex) {
            log.warn("Claude API request failed: {}", ex.getMessage());
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,
                    "Reading the receipt took too long. Try again, or enter this receipt by hand.");
        }

        MessageResponse response;
        try {
            response = JSON.readValue(responseBody, MessageResponse.class);
        } catch (JacksonException ex) {
            throw unreadable();
        }
        if (response.usage() != null) {
            log.info("Read a receipt with {}: {} input / {} output tokens", model,
                    response.usage().get("input_tokens"), response.usage().get("output_tokens"));
        }
        if ("refusal".equals(response.stopReason()) || "max_tokens".equals(response.stopReason())) {
            log.warn("Receipt read stopped with {}", response.stopReason());
            throw unreadable();
        }
        String text = response.content() == null ? null : response.content().stream()
                .filter(block -> "text".equals(block.type()) && block.text() != null)
                .map(Block::text)
                .findFirst()
                .orElse(null);
        if (text == null) {
            throw unreadable();
        }
        try {
            ReceiptDraft draft = JSON.readValue(text, ReceiptDraft.class);
            return draft.items() == null
                    ? new ReceiptDraft(draft.storeName(), draft.storeLocation(), draft.purchaseDate(),
                            draft.purchaseTime(), draft.currency(),
                            List.of(), draft.subtotal(), draft.tax(), draft.total(), draft.notes())
                    : draft;
        } catch (JacksonException ex) {
            log.warn("Receipt reply did not parse: {}", ex.getOriginalMessage());
            throw unreadable();
        }
    }

    /** The Messages API request: photos first, then instructions, with the reply held to the schema. */
    Map<String, Object> requestBody(List<Photo> photos, List<String> knownItems, List<String> knownStores) {
        List<Object> content = new ArrayList<>();
        for (Photo photo : photos) {
            content.add(Map.of(
                    "type", "image",
                    "source", Map.of(
                            "type", "base64",
                            "media_type", photo.mediaType(),
                            "data", Base64.getEncoder().encodeToString(photo.data()))));
        }
        content.add(Map.of("type", "text", "text", userText(photos.size(), knownItems, knownStores)));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", MAX_TOKENS);
        body.put("system", SYSTEM_PROMPT);
        body.put("messages", List.of(Map.of("role", "user", "content", content)));
        body.put("output_config", Map.of("format", Map.of("type", "json_schema", "schema", schema)));
        return body;
    }

    private static String userText(int photoCount, List<String> knownItems, List<String> knownStores) {
        StringBuilder text = new StringBuilder(photoCount == 1
                ? "Read this receipt."
                : "These " + photoCount + " photos are one receipt, top to bottom. Read it as a single receipt.");
        text.append("\n\nThe household's existing stores: ")
                .append(knownStores.isEmpty() ? "(none yet)" : String.join("; ", knownStores));
        text.append("\n\nThe household's existing item names: ")
                .append(knownItems.isEmpty() ? "(none yet)" : String.join("; ", knownItems));
        return text.toString();
    }

    private static ResponseStatusException unreadable() {
        return new ResponseStatusException(HttpStatus.valueOf(422),
                "Couldn't read this receipt. Try a clearer, flatter photo in good light, or enter it by hand.");
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 300 ? text.substring(0, 300) + "…" : text;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadSchema() {
        try (InputStream in = new ClassPathResource("receipt-schema.json").getInputStream()) {
            return JSON.readValue(in, Map.class);
        } catch (IOException ex) {
            throw new UncheckedIOException("receipt-schema.json is missing", ex);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MessageResponse(List<Block> content,
                           @JsonProperty("stop_reason") String stopReason,
                           Map<String, Object> usage) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Block(String type, String text) {
    }
}
