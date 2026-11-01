package com.viby.core.tools;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/** Small blocking HTTP helper for release metadata and verified binary downloads. */
public class Http {

    public interface Progress {
        void onProgress(long downloadedBytes, long totalBytes);
    }

    private static final String USER_AGENT = "VibyDesktop";

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    public String getString(URI uri) throws IOException, InterruptedException {
        HttpResponse<String> response = client.send(request(uri),
                HttpResponse.BodyHandlers.ofString());
        check(uri, response.statusCode());
        return response.body();
    }

    /** Final URL after redirects, used for mirrors whose checksum sits next to the file. */
    public URI resolve(URI uri) throws IOException, InterruptedException {
        HttpRequest head = HttpRequest.newBuilder(uri)
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .header("User-Agent", USER_AGENT)
                .build();
        HttpResponse<Void> response = client.send(head, HttpResponse.BodyHandlers.discarding());
        check(uri, response.statusCode());
        return response.uri();
    }

    /**
     * Streams {@code uri} into {@code target}; fails and deletes the file when the SHA-256
     * does not match {@code expectedSha256}.
     */
    public void download(URI uri, Path target, @Nullable String expectedSha256,
                         @Nullable Progress progress) throws IOException, InterruptedException {
        HttpResponse<InputStream> response = client.send(request(uri),
                HttpResponse.BodyHandlers.ofInputStream());
        check(uri, response.statusCode());
        long total = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        MessageDigest digest = sha256();
        try (InputStream input = response.body();
             OutputStream output = Files.newOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            long done = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (Thread.interrupted()) {
                    throw new InterruptedException();
                }
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                done += read;
                if (progress != null) {
                    progress.onProgress(done, total);
                }
            }
        } catch (IOException | InterruptedException e) {
            Files.deleteIfExists(target);
            throw e;
        }
        String actual = HexFormat.of().formatHex(digest.digest());
        if (expectedSha256 != null && !actual.equalsIgnoreCase(expectedSha256.trim())) {
            Files.deleteIfExists(target);
            throw new IOException("Checksum mismatch for " + uri);
        }
    }

    private static HttpRequest request(URI uri) {
        return HttpRequest.newBuilder(uri)
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofMinutes(10))
                .GET()
                .build();
    }

    private static void check(URI uri, int status) throws IOException {
        if (status < 200 || status >= 300) {
            throw new IOException("HTTP " + status + " for " + uri);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
