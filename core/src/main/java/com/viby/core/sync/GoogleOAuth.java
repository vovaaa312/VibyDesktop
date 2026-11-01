package com.viby.core.sync;

import com.sun.net.httpserver.HttpServer;

import org.jspecify.annotations.Nullable;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Google sign-in for the YouTube Data API using the installed-app flow: the system browser,
 * a loopback redirect on 127.0.0.1 and PKCE. Replaces Android's Google Identity client.
 */
public final class GoogleOAuth {

    public static final String YOUTUBE_SCOPE = "https://www.googleapis.com/auth/youtube.force-ssl";
    private static final String CONFIG_RESOURCE = "/google_oauth.json";
    private static final String REVOKE_URI = "https://oauth2.googleapis.com/revoke";

    /** The "installed" section of a Desktop-app client_secret.json. */
    public record ClientConfig(String clientId, String clientSecret, String authUri, String tokenUri) {

        static ClientConfig parse(String json) {
            JSONObject root = new JSONObject(json);
            JSONObject installed = root.optJSONObject("installed");
            if (installed == null) {
                throw new IllegalArgumentException("Нужен OAuth client типа «Desktop app»");
            }
            return new ClientConfig(installed.getString("client_id"), installed.getString("client_secret"),
                    installed.optString("auth_uri", "https://accounts.google.com/o/oauth2/auth"),
                    installed.optString("token_uri", "https://oauth2.googleapis.com/token"));
        }

        /** Bundled at build time from client_secret*.json in the project root (not in git). */
        public static @Nullable ClientConfig bundled() {
            try (InputStream input = GoogleOAuth.class.getResourceAsStream(CONFIG_RESOURCE)) {
                return input != null ? parse(new String(input.readAllBytes(), StandardCharsets.UTF_8)) : null;
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }
    }

    private final @Nullable ClientConfig config;
    private final Path tokenFile;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private final SecureRandom random = new SecureRandom();
    private @Nullable String accessToken;
    private long accessTokenExpiresAt;

    public GoogleOAuth(@Nullable ClientConfig config, Path tokenFile) {
        this.config = config;
        this.tokenFile = tokenFile;
    }

    /** False when the build has no OAuth client; account features are hidden then. */
    public boolean isConfigured() {
        return config != null;
    }

    public synchronized boolean isSignedIn() {
        return refreshToken() != null;
    }

    /**
     * Opens the consent page and waits for the browser to come back.
     *
     * @param openBrowser receives the authorization URL (Desktop.browse in the app)
     */
    public void signIn(Consumer<URI> openBrowser, Duration timeout) throws IOException, InterruptedException {
        ClientConfig client = requireConfig();
        String verifier = randomToken(64);
        String state = randomToken(24);
        CompletableFuture<Map<String, String>> callback = new CompletableFuture<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            boolean ok = query.containsKey("code");
            byte[] page = ("<!doctype html><meta charset=utf-8><title>Viby</title>"
                    + "<body style=\"font-family:sans-serif;background:#121212;color:#fff;text-align:center;padding-top:80px\">"
                    + "<h2>" + (ok ? "Готово! Можно вернуться в Viby." : "Вход отменён.") + "</h2>"
                    + "<p>Эту вкладку можно закрыть.</p></body>").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, page.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(page);
            }
            if (query.containsKey("code") || query.containsKey("error")) {
                callback.complete(query);
            }
        });
        server.start();
        try {
            String redirectUri = "http://127.0.0.1:" + server.getAddress().getPort();
            openBrowser.accept(URI.create(client.authUri()
                    + "?response_type=code"
                    + "&client_id=" + encode(client.clientId())
                    + "&redirect_uri=" + encode(redirectUri)
                    + "&scope=" + encode(YOUTUBE_SCOPE)
                    + "&code_challenge=" + challenge(verifier)
                    + "&code_challenge_method=S256"
                    + "&state=" + state
                    + "&access_type=offline&prompt=consent"));
            Map<String, String> result;
            try {
                result = callback.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                throw new IOException("Вход не завершён: истекло время ожидания");
            } catch (ExecutionException e) {
                throw new IOException(e.getCause());
            }
            if (result.containsKey("error")) {
                throw new IOException("Google: " + result.get("error"));
            }
            if (!state.equals(result.get("state"))) {
                throw new IOException("Ответ Google не прошёл проверку state");
            }
            JSONObject token = tokenRequest(form(
                    "grant_type", "authorization_code",
                    "code", result.get("code"),
                    "redirect_uri", redirectUri,
                    "client_id", client.clientId(),
                    "client_secret", client.clientSecret(),
                    "code_verifier", verifier));
            String refresh = token.optString("refresh_token", "");
            if (refresh.isEmpty()) {
                throw new IOException("Google не вернул refresh token");
            }
            synchronized (this) {
                storeRefreshToken(refresh);
                acceptAccessToken(token);
            }
        } finally {
            server.stop(0);
        }
    }

    /** A valid access token, refreshed when it expires within a minute. */
    public synchronized String accessToken() throws IOException, InterruptedException {
        if (accessToken != null && System.currentTimeMillis() < accessTokenExpiresAt - 60_000) {
            return accessToken;
        }
        String refresh = refreshToken();
        if (refresh == null) {
            throw new IOException("Нужно войти в аккаунт Google");
        }
        ClientConfig client = requireConfig();
        JSONObject token;
        try {
            token = tokenRequest(form(
                    "grant_type", "refresh_token",
                    "refresh_token", refresh,
                    "client_id", client.clientId(),
                    "client_secret", client.clientSecret()));
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("invalid_grant")) {
                // revoked in the Google account or expired (Testing apps expire after 7 days)
                Files.deleteIfExists(tokenFile);
                throw new IOException("Вход в Google истёк — войдите снова", e);
            }
            throw e;
        }
        acceptAccessToken(token);
        return accessToken;
    }

    public void signOut() {
        String refresh;
        synchronized (this) {
            refresh = refreshToken();
            accessToken = null;
            accessTokenExpiresAt = 0;
            try {
                Files.deleteIfExists(tokenFile);
            } catch (IOException ignored) {
                // a stale file only keeps the account signed in locally
            }
        }
        if (refresh != null) {
            try {
                http.send(HttpRequest.newBuilder(URI.create(REVOKE_URI))
                                .header("Content-Type", "application/x-www-form-urlencoded")
                                .POST(HttpRequest.BodyPublishers.ofString(form("token", refresh)))
                                .build(),
                        HttpResponse.BodyHandlers.discarding());
            } catch (IOException ignored) {
                // revocation is best effort; the local token is gone either way
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private JSONObject tokenRequest(String body) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(requireConfig().tokenUri()))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        JSONObject json = response.body().isBlank() ? new JSONObject() : new JSONObject(response.body());
        if (response.statusCode() != 200) {
            throw new IOException("Google token error: " + json.optString("error", "HTTP " + response.statusCode())
                    + " " + json.optString("error_description", ""));
        }
        return json;
    }

    private void acceptAccessToken(JSONObject token) {
        accessToken = token.getString("access_token");
        accessTokenExpiresAt = System.currentTimeMillis() + token.optLong("expires_in", 3600) * 1000;
    }

    private @Nullable String refreshToken() {
        try {
            if (!Files.isRegularFile(tokenFile)) {
                return null;
            }
            String value = new JSONObject(Files.readString(tokenFile)).optString("refresh_token", "");
            return value.isEmpty() ? null : value;
        } catch (Exception e) {
            return null;
        }
    }

    private void storeRefreshToken(String refresh) throws IOException {
        Files.createDirectories(tokenFile.toAbsolutePath().getParent());
        Path temp = tokenFile.resolveSibling(tokenFile.getFileName() + ".tmp");
        Files.writeString(temp, new JSONObject().put("refresh_token", refresh).toString());
        temp.toFile().setReadable(false, false);
        temp.toFile().setReadable(true, true);
        Files.move(temp, tokenFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private ClientConfig requireConfig() throws IOException {
        if (config == null) {
            throw new IOException("Вход через Google не настроен в этой сборке");
        }
        return config;
    }

    private String randomToken(int bytes) {
        byte[] buffer = new byte[bytes];
        random.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }

    static String challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static Map<String, String> parseQuery(@Nullable String query) {
        Map<String, String> result = new HashMap<>();
        if (query == null) {
            return result;
        }
        for (String part : query.split("&")) {
            int separator = part.indexOf('=');
            if (separator > 0) {
                result.put(URLDecoder.decode(part.substring(0, separator), StandardCharsets.UTF_8),
                        URLDecoder.decode(part.substring(separator + 1), StandardCharsets.UTF_8));
            }
        }
        return result;
    }

    private static String form(String... pairs) {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < pairs.length; i += 2) {
            if (body.length() > 0) {
                body.append('&');
            }
            body.append(encode(pairs[i])).append('=').append(encode(pairs[i + 1]));
        }
        return body.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
