package net.strata.launcher.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class MicrosoftDeviceCodeAuthenticator {
    private static final String MICROSOFT_AUTH =
            "https://login.microsoftonline.com/consumers/oauth2/v2.0";
    private static final String XBOX_AUTH = "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XSTS_AUTH = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String MINECRAFT_AUTH =
            "https://api.minecraftservices.com/authentication/login_with_xbox";
    private static final String ENTITLEMENTS =
            "https://api.minecraftservices.com/entitlements/mcstore";
    private static final String PROFILE = "https://api.minecraftservices.com/minecraft/profile";

    private final String clientId;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public MicrosoftDeviceCodeAuthenticator(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException(
                    "Configure a Microsoft Entra public-client application ID to enable online sign-in.");
        }
        this.clientId = clientId;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public DeviceCode begin() throws IOException, InterruptedException {
        JsonNode response = postForm(URI.create(MICROSOFT_AUTH + "/devicecode"), Map.of(
                "client_id", clientId,
                "scope", "XboxLive.signin offline_access"));
        return new DeviceCode(text(response, "device_code"), text(response, "user_code"),
                text(response, "verification_uri"), response.path("message").asText(),
                Math.max(1, response.path("interval").asInt(5)),
                Math.max(1, response.path("expires_in").asInt(900)));
    }

    public AuthenticationSession authenticate(DeviceCode deviceCode, AccountStore accountStore,
            PollListener listener) throws IOException, InterruptedException {
        Instant deadline = Instant.now().plusSeconds(deviceCode.expiresInSeconds());
        String microsoftToken = pollMicrosoft(deviceCode, deadline, listener);
        String xboxToken = authenticateXbox(microsoftToken);
        JsonNode xstsToken = authorizeXsts(xboxToken);
        JsonNode minecraftAuth = postJson(URI.create(MINECRAFT_AUTH), Map.of(
                "identityToken", "XBL3.0 x=" + text(xstsToken, "uhs")
                        + ";" + text(xstsToken, "Token")));
        String minecraftToken = text(minecraftAuth, "access_token");
        verifyEntitlements(minecraftToken);
        JsonNode profile = getJson(URI.create(PROFILE), minecraftToken);
        String username = text(profile, "name");
        String profileId = text(profile, "id");
        AccountProfile account = accountStore.addMicrosoft(username, profileId);
        long expiresIn = minecraftAuth.path("expires_in").asLong(0);
        if (expiresIn <= 0) {
            throw new IOException("Minecraft authentication returned an invalid expiry.");
        }
        return new AuthenticationSession(account, minecraftToken,
                Instant.now().plusSeconds(expiresIn), text(xstsToken, "xuid"));
    }

    private String pollMicrosoft(DeviceCode code, Instant deadline, PollListener listener)
            throws IOException, InterruptedException {
        while (Instant.now().isBefore(deadline)) {
            listener.waiting();
            Thread.sleep(code.intervalSeconds() * 1000L);
            JsonNode response = postForm(URI.create(MICROSOFT_AUTH + "/token"), Map.of(
                    "grant_type", "urn:ietf:params:oauth:grant-type:device_code",
                    "client_id", clientId,
                    "device_code", code.deviceCode()), true);
            String error = optionalText(response, "error");
            if (error == null) {
                return text(response, "access_token");
            }
            if (error.equals("authorization_pending")) {
                continue;
            }
            if (error.equals("slow_down")) {
                Thread.sleep(5000);
                continue;
            }
            if (error.equals("authorization_declined")) {
                throw new IOException("Microsoft account sign-in was declined.");
            }
            if (error.equals("expired_token")) {
                break;
            }
            throw new IOException("Microsoft device-code sign-in failed: "
                    + response.path("error_description").asText(error));
        }
        throw new IOException("Microsoft device code expired before sign-in completed.");
    }

    private String authenticateXbox(String microsoftToken) throws IOException, InterruptedException {
        JsonNode response = postJson(URI.create(XBOX_AUTH), Map.of(
                "Properties", Map.of(
                        "AuthMethod", "RPS",
                        "SiteName", "user.auth.xboxlive.com",
                        "RpsTicket", "d=" + microsoftToken),
                "RelyingParty", "http://auth.xboxlive.com",
                "TokenType", "JWT"));
        return text(response, "Token");
    }

    private JsonNode authorizeXsts(String xboxToken) throws IOException, InterruptedException {
        JsonNode response = postJson(URI.create(XSTS_AUTH), Map.of(
                "Properties", Map.of(
                        "SandboxId", "RETAIL",
                        "UserTokens", new String[] {xboxToken}),
                "RelyingParty", "rp://api.minecraftservices.com/",
                "TokenType", "JWT"));
        JsonNode claims = response.path("DisplayClaims").path("xui");
        if (!claims.isArray() || claims.isEmpty()) {
            throw new IOException("Xbox authorization did not return a user hash.");
        }
        String userHash = text(claims.get(0), "uhs");
        String xuid = claims.get(0).path("xid").asText("");
        return mapper.valueToTree(Map.of("Token", text(response, "Token"),
                "uhs", userHash, "xuid", xuid));
    }

    private void verifyEntitlements(String minecraftToken) throws IOException, InterruptedException {
        JsonNode response = getJson(URI.create(ENTITLEMENTS), minecraftToken);
        JsonNode items = response.path("items");
        if (!items.isArray() || items.isEmpty()) {
            throw new IOException("This Microsoft account has no Minecraft Java entitlement.");
        }
    }

    private JsonNode postForm(URI uri, Map<String, String> values)
            throws IOException, InterruptedException {
        String body = values.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(java.util.stream.Collectors.joining("&"));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return sendJson(request, false);
    }

    private JsonNode postForm(URI uri, Map<String, String> values, boolean allowErrorResponse)
            throws IOException, InterruptedException {
        String body = values.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(java.util.stream.Collectors.joining("&"));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return sendJson(request, allowErrorResponse);
    }

    private JsonNode postJson(URI uri, Object body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        return sendJson(request, false);
    }

    private JsonNode getJson(URI uri, String bearerToken) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + bearerToken)
                .GET().build();
        return sendJson(request, false);
    }

    private JsonNode sendJson(HttpRequest request, boolean allowErrorResponse)
            throws IOException, InterruptedException {
        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode body;
        try {
            body = mapper.readTree(response.body());
        } catch (IOException exception) {
            throw new IOException("Authentication service returned invalid JSON.", exception);
        }
        if (!allowErrorResponse && (response.statusCode() < 200 || response.statusCode() >= 300)) {
            String error = body.path("errorMessage").asText(
                    body.path("error_description").asText(
                            body.path("Message").asText(body.path("error").asText("request failed"))));
            throw new IOException("Authentication service returned HTTP "
                    + response.statusCode() + ": " + error);
        }
        return body;
    }

    private static String text(JsonNode node, String field) throws IOException {
        String value = optionalText(node, field);
        if (value == null || value.isBlank()) {
            throw new IOException("Authentication response is missing " + field + ".");
        }
        return value;
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public record DeviceCode(
            String deviceCode,
            String userCode,
            String verificationUri,
            String message,
            int intervalSeconds,
            int expiresInSeconds) {
        @Override
        public String toString() {
            return "DeviceCode[userCode=" + userCode + ", verificationUri=" + verificationUri
                    + ", deviceCode=<redacted>, expiresInSeconds=" + expiresInSeconds + "]";
        }
    }

    @FunctionalInterface
    public interface PollListener {
        void waiting();
    }
}
