package it.pratico.garminmcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Minimal client for the Garmin Connect Personal API.
 * <p>
 * Flow: personal API key -> POST token endpoint -> OAuth2 access token (valid 24h, no refresh token)
 * -> "Authorization: Bearer ..." on every call. The token is cached and re-obtained when it expires
 * or when a call answers 401.
 */
@Component
public class GarminApiClient {

    private static final Logger log = LoggerFactory.getLogger(GarminApiClient.class);
    private static final String TOKEN_PATH =
            "/personalapitoken-service/personal-api/personal-api-key/token";
    /** Re-obtain the token a bit before the documented 24h validity. */
    private static final Duration DEFAULT_TOKEN_TTL = Duration.ofHours(23);

    private final GarminProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private String accessToken;
    private Instant tokenExpiresAt = Instant.EPOCH;

    public GarminApiClient(GarminProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------ public API

    /** GET a JSON resource. {@code path} must start with "/" (e.g. "/hrv-service/hrv/2026-10-25"). */
    public JsonNode getJson(String path, Map<String, String> query) {
        byte[] body = get(path, query, "application/json");
        try {
            return mapper.readTree(body);
        } catch (IOException e) {
            throw new GarminApiException("Response is not JSON: " + preview(body), e);
        }
    }

    /** GET a binary resource (e.g. activity file download). */
    public byte[] getBytes(String path, Map<String, String> query) {
        return get(path, query, "*/*");
    }

    // ------------------------------------------------------------------ internals

    private byte[] get(String path, Map<String, String> query, String accept) {
        if (path == null || !path.startsWith("/") || path.contains("://") || path.contains("..")) {
            throw new IllegalArgumentException("path must be a relative API path starting with '/'");
        }
        URI uri = URI.create(props.baseUrl() + path + toQueryString(query));
        HttpResponse<byte[]> resp = send(uri, accept);
        if (resp.statusCode() == 401) {
            log.info("401 from {}, refreshing token and retrying once", path);
            invalidateToken();
            resp = send(uri, accept);
        }
        if (resp.statusCode() == 406 && !"*/*".equals(accept)) {
            log.info("406 from {} with Accept {}, retrying with */*", path, accept);
            resp = send(uri, "*/*");
        }
        if (resp.statusCode() / 100 != 2) {
            throw new GarminApiException("GET " + path + " -> HTTP " + resp.statusCode() + ": " + preview(resp.body()));
        }
        return resp.body();
    }

    private HttpResponse<byte[]> send(URI uri, String accept) {
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + token())
                .header("Accept", accept)
                .GET()
                .build();
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new GarminApiException("Network error calling " + uri.getPath() + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GarminApiException("Interrupted", e);
        }
    }

    private synchronized String token() {
        if (accessToken != null && Instant.now().isBefore(tokenExpiresAt)) {
            return accessToken;
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(props.baseUrl() + TOKEN_PATH))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "ApiKey " + apiKey())
                .header("Accept", "*/*")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            String contentType = resp.headers().firstValue("Content-Type").orElse("none");
            if (resp.statusCode() / 100 != 2) {
                throw new GarminApiException("Token request failed: HTTP " + resp.statusCode()
                        + " (Content-Type: " + contentType + ", body: "
                        + preview(resp.body().getBytes(StandardCharsets.UTF_8))
                        + ") - check that the personal API key is valid and not expired");
            }
            String body = resp.body() == null ? "" : resp.body().trim();
            if (body.isEmpty()) {
                throw new GarminApiException("Token response is empty (Content-Type: " + contentType + ")");
            }
            if (!body.startsWith("{")) {
                // Plain-text token, possibly wrapped in quotes
                accessToken = body.replaceAll("^\"|\"$", "");
                tokenExpiresAt = Instant.now().plus(DEFAULT_TOKEN_TTL);
                log.info("Obtained Garmin access token (plain text, {}), valid until {}", contentType, tokenExpiresAt);
                return accessToken;
            }
            JsonNode json = mapper.readTree(body);
            String token = firstText(json, "access_token", "accessToken", "token");
            if (token == null) {
                throw new GarminApiException("Token response has no access token field (Content-Type: "
                        + contentType + "). Fields: " + fieldNames(json));
            }
            long expiresIn = json.path("expires_in").asLong(json.path("expiresIn").asLong(0));
            Duration ttl = expiresIn > 120 ? Duration.ofSeconds(expiresIn - 60) : DEFAULT_TOKEN_TTL;
            accessToken = token;
            tokenExpiresAt = Instant.now().plus(ttl);
            log.info("Obtained Garmin access token, valid until {}", tokenExpiresAt);
            return accessToken;
        } catch (IOException e) {
            throw new GarminApiException("Network error obtaining token: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GarminApiException("Interrupted", e);
        }
    }

    private synchronized void invalidateToken() {
        accessToken = null;
        tokenExpiresAt = Instant.EPOCH;
    }

    private String apiKey() {
        String env = System.getenv("GARMIN_PERSONAL_API_KEY");
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        Path file = Path.of(props.apiKeyFile());
        try {
            String key = Files.readString(file).trim();
            if (key.isEmpty()) {
                throw new GarminApiException("API key file is empty: " + file);
            }
            return key;
        } catch (IOException e) {
            throw new GarminApiException("No API key: set GARMIN_PERSONAL_API_KEY or create " + file, e);
        }
    }

    private static String toQueryString(Map<String, String> query) {
        if (query == null || query.isEmpty()) {
            return "";
        }
        Map<String, String> clean = new LinkedHashMap<>();
        query.forEach((k, v) -> {
            if (k != null && v != null && !v.isBlank()) {
                clean.put(k, v);
            }
        });
        if (clean.isEmpty()) {
            return "";
        }
        return "?" + clean.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .collect(Collectors.joining("&"));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String firstText(JsonNode json, String... names) {
        for (String n : names) {
            JsonNode v = json.get(n);
            if (v != null && v.isTextual() && !v.asText().isBlank()) {
                return v.asText();
            }
        }
        return null;
    }

    private static String fieldNames(JsonNode json) {
        StringBuilder sb = new StringBuilder();
        json.fieldNames().forEachRemaining(n -> sb.append(n).append(' '));
        return sb.toString().trim();
    }

    private static String preview(byte[] body) {
        if (body == null) {
            return "";
        }
        String s = new String(body, StandardCharsets.UTF_8);
        return s.length() > 500 ? s.substring(0, 500) + "..." : s;
    }

    public static class GarminApiException extends RuntimeException {
        public GarminApiException(String msg) {
            super(msg);
        }

        public GarminApiException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }
}
