package com.usedlog.barcode;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;
import com.usedlog.barcode.ProductLookup.Product;

/** The Open Food Facts client against a local stand-in: the request it sends and the names it builds. */
class OpenFoodFactsLookupTests {

    private HttpServer server;
    private volatile int status;
    private volatile String reply;
    private volatile String requestPath;
    private volatile String userAgent;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestPath = exchange.getRequestURI().toString();
            userAgent = exchange.getRequestHeaders().getFirst("User-Agent");
            byte[] out = reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(out);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private OpenFoodFactsLookup lookup() {
        return new OpenFoodFactsLookup("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Test
    void asksForOneProductAndBuildsAName() {
        status = 200;
        reply = """
                {"code":"0041303012345","status":1,"status_verbose":"product found",
                 "product":{"product_name":"2% Reduced Fat Ultra-Filtered Milk","brands":"fairlife,Coca-Cola",
                            "quantity":"52 fl oz (1.53 L)"}}
                """;

        Optional<Product> found = lookup().find("0041303012345");

        assertThat(requestPath).startsWith("/api/v2/product/0041303012345?fields=");
        assertThat(userAgent).startsWith("PantryTracker/");
        assertThat(found).contains(new Product("Fairlife 2% Reduced Fat Ultra-Filtered Milk, 52 fl oz",
                "Fairlife", "52 fl oz", "Open Food Facts"));
    }

    @Test
    void notInTheDatabaseOrAnErrorMeansNoSuggestion() {
        status = 404;
        reply = """
                {"code":"0000000000000","status":0,"status_verbose":"product not found"}
                """;
        assertThat(lookup().find("0036000291452")).isEmpty();

        status = 503;
        reply = "<html>busy</html>";
        assertThat(lookup().find("0036000291452")).isEmpty();
    }

    @Test
    void turnedOffWithAnEmptyUrl() {
        status = 200;
        reply = "{}";
        assertThat(new OpenFoodFactsLookup("").find("0036000291452")).isEmpty();
        assertThat(requestPath).isNull();
    }

    @Test
    void namesKeepTheBrandAndSizeOnceAndFitEightyCharacters() {
        assertThat(OpenFoodFactsLookup.parse("""
                {"status":1,"product":{"product_name":"Goya Black Bean Soup","brands":"Goya","quantity":"15 oz"}}
                """)).map(Product::name).contains("Goya Black Bean Soup, 15 oz");

        assertThat(OpenFoodFactsLookup.parse("""
                {"status":1,"product":{"product_name":"","product_name_en":"Large Eggs, 12 ct","brands":"Wegmans",
                 "quantity":"12 ct"}}
                """)).map(Product::name).contains("Wegmans Large Eggs, 12 ct");

        String longName = "Organic Extra Virgin Cold Pressed Unfiltered Olive Oil From Single Estate Groves In Crete";
        String shortened = OpenFoodFactsLookup.parse("""
                {"status":1,"product":{"product_name":"%s","brands":"Acme","quantity":"500 ml"}}
                """.formatted(longName)).map(Product::name).orElseThrow();
        assertThat(shortened.length()).isLessThanOrEqualTo(80);
        assertThat(shortened).startsWith("Acme Organic Extra Virgin").doesNotEndWith(" ");

        assertThat(OpenFoodFactsLookup.parse("""
                {"status":1,"product":{"brands":"Acme"}}
                """)).isEmpty();
        assertThat(OpenFoodFactsLookup.parse("not json")).isEmpty();
    }
}
