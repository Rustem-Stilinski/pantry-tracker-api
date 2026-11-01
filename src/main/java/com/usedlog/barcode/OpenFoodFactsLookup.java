package com.usedlog.barcode;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import tools.jackson.databind.json.JsonMapper;

/**
 * Suggests a name for a barcode from Open Food Facts (free, no key; mostly food and drink, some household
 * products). Only the barcode is sent. Answers that take longer than a few seconds are dropped, so a slow
 * lookup never holds up a scan.
 *
 * Configuration: PRODUCT_LOOKUP_URL (default https://world.openfoodfacts.org); set it empty to turn
 * suggestions off.
 */
@Component
public class OpenFoodFactsLookup implements ProductLookup {

    static final String SOURCE = "Open Food Facts";
    private static final Logger log = LoggerFactory.getLogger(OpenFoodFactsLookup.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String USER_AGENT =
            "PantryTracker/1.0 (+https://github.com/Rustem-Stilinski/pantry-tracker-api)";
    private static final int MAX_NAME = 80;

    private final RestClient http;
    private final boolean enabled;

    public OpenFoodFactsLookup(@Value("${app.barcodes.lookup-url:https://world.openfoodfacts.org}") String baseUrl) {
        this.enabled = baseUrl != null && !baseUrl.isBlank();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        requestFactory.setReadTimeout(Duration.ofSeconds(4));
        this.http = RestClient.builder()
                .baseUrl(enabled ? baseUrl.trim() : "http://localhost")
                .requestFactory(requestFactory)
                .defaultHeader("User-Agent", USER_AGENT)
                .build();
    }

    @Override
    public Optional<Product> find(String code) {
        if (!enabled || code == null || !code.matches("\\d{8,14}")) {
            return Optional.empty();
        }
        try {
            String body = http.get()
                    .uri("/api/v2/product/{code}?fields=product_name,product_name_en,brands,quantity", code)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);
            return body == null ? Optional.empty() : parse(body);
        } catch (RestClientResponseException ex) {
            // 404 is the usual "not in the database".
            if (ex.getStatusCode().value() != 404) {
                log.info("Open Food Facts answered {} for a barcode", ex.getStatusCode().value());
            }
            return Optional.empty();
        } catch (RuntimeException ex) {
            log.info("Open Food Facts lookup failed: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    /** Builds the suggestion from a reply, or empty when the product isn't there or has no name. */
    static Optional<Product> parse(String body) {
        Reply reply;
        try {
            reply = JSON.readValue(body, Reply.class);
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
        if (reply == null || reply.status() != 1 || reply.product() == null) {
            return Optional.empty();
        }
        Found product = reply.product();
        String productName = firstPresent(clean(product.productName()), clean(product.productNameEn()));
        if (productName == null) {
            return Optional.empty();
        }
        String brand = brand(product.brands());
        String size = size(product.quantity());

        String name = productName;
        if (brand != null && !contains(name, brand)) {
            name = brand + " " + name;
        }
        if (size != null && !contains(name, size)) {
            name = name + ", " + size;
        }
        return Optional.of(new Product(shorten(name), brand, size, SOURCE));
    }

    /** "fairlife,Coca-Cola" -> "Fairlife". */
    private static String brand(String brands) {
        String cleaned = clean(brands);
        if (cleaned == null) {
            return null;
        }
        String first = clean(cleaned.split(",")[0]);
        if (first == null) {
            return null;
        }
        return first.equals(first.toLowerCase(Locale.ROOT))
                ? Character.toUpperCase(first.charAt(0)) + first.substring(1)
                : first;
    }

    /** "52 fl oz (1.53 L)" -> "52 fl oz". */
    private static String size(String quantity) {
        String cleaned = clean(quantity);
        if (cleaned == null) {
            return null;
        }
        return clean(cleaned.replaceAll("\\s*\\(.*?\\)", ""));
    }

    private static boolean contains(String text, String part) {
        return text.toLowerCase(Locale.ROOT).contains(part.toLowerCase(Locale.ROOT));
    }

    /** Fits the 80-character item name, cutting at a word where possible. */
    private static String shorten(String name) {
        if (name.length() <= MAX_NAME) {
            return name;
        }
        String cut = name.substring(0, MAX_NAME);
        int space = cut.lastIndexOf(' ');
        return (space > MAX_NAME / 2 ? cut.substring(0, space) : cut).replaceAll("[,\\s]+$", "");
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.trim().replaceAll("\\s+", " ");
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static String firstPresent(String a, String b) {
        return a != null ? a : b;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Reply(int status, Found product) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Found(@JsonProperty("product_name") String productName,
                 @JsonProperty("product_name_en") String productNameEn,
                 String brands,
                 String quantity) {
    }
}
