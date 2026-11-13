package com.viby.core.lyrics;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Synced lyrics from LRCLIB (lrclib.net), an open lyrics database used by many open-source
 * players. Only the artist, title and length of the track are sent. YouTube titles carry noise
 * ("Artist - Song (Official Video) [4K]"), so the query is cleaned first.
 */
public final class LrclibLyrics {

    private static final String API = "https://lrclib.net/api/";
    private static final String USER_AGENT = "VibyDesktop (https://github.com/vovaaa312/VibyDesktop)";
    /** A music video is often a little longer or shorter than the recording. */
    private static final long DURATION_TOLERANCE_SEC = 4;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** What LRCLIB has for a track; {@code synced} is empty when it has only plain text. */
    public record Result(String plain, List<TimedLine> synced) {
    }

    record Query(String artist, String title, long durationSec) {
    }

    private LrclibLyrics() {
    }

    /** Null when LRCLIB does not know the track. Network failures are thrown. */
    public static @Nullable Result find(@Nullable String uploader, String title, long durationMs) throws Exception {
        Query query = query(uploader, title, durationMs);
        if (query.title().isEmpty()) {
            return null;
        }
        Result exact = null;
        if (!query.artist().isEmpty()) {
            String body = get("get?" + params("artist_name", query.artist(), "track_name", query.title(),
                    "duration", String.valueOf(query.durationSec())));
            if (body != null) {
                exact = result(new JSONObject(body));
                if (exact != null && !exact.synced().isEmpty()) {
                    return exact;
                }
            }
        }
        // the exact lookup misses other spellings and versions: search, prefer synced of a close length
        String body = get("search?" + params("q", (query.artist() + " " + query.title()).trim()));
        Result found = body != null ? bestMatch(new JSONArray(body), query) : null;
        if (found == null || found.synced().isEmpty()) {
            // the artist may be written differently there (Улица Восток / Ulitsa Vostok): by title alone
            String byTitle = query.artist().isEmpty() ? null : get("search?" + params("track_name", query.title()));
            Result other = byTitle != null ? bestMatch(new JSONArray(byTitle), query) : null;
            if (other != null && (found == null || !other.synced().isEmpty())) {
                found = other;
            }
        }
        if (found != null && (!found.synced().isEmpty() || exact == null)) {
            return found;
        }
        return exact;
    }

    static @Nullable Result bestMatch(JSONArray results, Query query) {
        List<JSONObject> candidates = new ArrayList<>();
        for (int i = 0; i < results.length(); i++) {
            JSONObject item = results.optJSONObject(i);
            if (item == null || item.optBoolean("instrumental")) {
                continue;
            }
            long duration = Math.round(item.optDouble("duration", 0));
            boolean closeLength = query.durationSec() <= 0
                    || Math.abs(duration - query.durationSec()) <= DURATION_TOLERANCE_SEC;
            if (closeLength && matches(item, query, Math.abs(duration - query.durationSec()))) {
                candidates.add(item);
            }
        }
        candidates.sort(Comparator
                .comparing((JSONObject item) -> item.optString("syncedLyrics", "").isEmpty())
                .thenComparingDouble(item -> Math.abs(item.optDouble("duration", 0) - query.durationSec())));
        return candidates.isEmpty() ? null : result(candidates.get(0));
    }

    /** The search also returns other songs: the title must be in the result's title. */
    private static boolean matches(JSONObject item, Query query, long lengthDifference) {
        String title = normalize(item.optString("trackName", ""));
        String artist = normalize(item.optString("artistName", ""));
        String wantedTitle = normalize(query.title());
        String wantedArtist = normalize(query.artist());
        boolean titleOk = title.contains(wantedTitle) || wantedTitle.contains(title);
        boolean artistOk = wantedArtist.isEmpty() || artist.contains(wantedArtist) || wantedArtist.contains(artist)
                || title.contains(wantedArtist);
        // same title and practically the same length: the artist is just spelled differently
        boolean sameRecording = title.equals(wantedTitle) && query.durationSec() > 0 && lengthDifference <= 2;
        return !title.isEmpty() && titleOk && (artistOk || sameRecording);
    }

    private static @Nullable Result result(JSONObject item) {
        String synced = item.optString("syncedLyrics", "");
        String plain = item.optString("plainLyrics", "");
        List<TimedLine> lines = synced.isEmpty() ? List.of() : parseLrc(synced);
        if (plain.isBlank() && !lines.isEmpty()) {
            StringBuilder text = new StringBuilder();
            lines.forEach(line -> text.append(line.text()).append('\n'));
            plain = text.toString();
        }
        return plain.isBlank() && lines.isEmpty() ? null : new Result(plain.trim(), lines);
    }

    // ---------------------------------------------------------------- query cleaning

    private static final Pattern BRACKETS = Pattern.compile("\\s*[(\\[【][^)\\]】]*[)\\]】]");
    private static final Pattern FEAT = Pattern.compile("(?i)\\s+(feat\\.?|ft\\.?|featuring)\\s.*$");
    private static final Pattern DASH = Pattern.compile("\\s+[-–—]\\s+");

    /**
     * "Улица Восток - Запомню (Directed by …)" by "Улица Восток" → Улица Восток / Запомню;
     * "Кукушка" by "Kino - Topic" → Kino / Кукушка; a label channel keeps the artist from the title.
     */
    static Query query(@Nullable String uploader, String rawTitle, long durationMs) {
        // YouTube Music channels ("Kino - Topic") have clean titles: "Song - Remastered" is one title
        boolean topic = uploader != null && uploader.trim().toLowerCase(Locale.ROOT).endsWith("- topic");
        String artist = uploader == null ? "" : uploader.trim()
                .replaceAll("(?i)\\s*-\\s*topic$", "")
                .replaceAll("(?i)vevo$", "")
                .replaceAll("(?i)\\s+official$", "")
                .trim();
        String title = BRACKETS.matcher(rawTitle).replaceAll("").trim();
        String[] parts = DASH.split(title, 2);
        if (parts.length == 2 && !topic) {
            String left = parts[0].trim();
            String right = parts[1].trim();
            boolean uploaderIsArtist = !artist.isEmpty()
                    && (normalize(left).contains(normalize(artist)) || normalize(artist).contains(normalize(left)));
            // "Artist - Song": the left side names the artist (a label channel uploaded it)
            if (!normalize(right).isEmpty()) {
                artist = uploaderIsArtist ? artist : FEAT.matcher(left).replaceAll("").trim();
                title = right;
            }
        }
        title = FEAT.matcher(title).replaceAll("").replaceAll("[\"«»“”]", "").trim();
        return new Query(artist, title, Math.round(durationMs / 1000.0));
    }

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    // ---------------------------------------------------------------- LRC

    private static final Pattern TIME = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]");

    /** "[01:06.38] Hast viel geweint" lines; a line may carry several timestamps. */
    static List<TimedLine> parseLrc(String lrc) {
        List<TimedLine> lines = new ArrayList<>();
        for (String raw : lrc.split("\\r?\\n")) {
            Matcher matcher = TIME.matcher(raw);
            List<Long> times = new ArrayList<>();
            int textStart = 0;
            while (matcher.find() && matcher.start() == textStart) {
                long minutes = Long.parseLong(matcher.group(1));
                long seconds = Long.parseLong(matcher.group(2));
                String fraction = matcher.group(3);
                long millis = 0;
                if (fraction != null) {
                    millis = Long.parseLong(fraction) * (fraction.length() == 1 ? 100 : fraction.length() == 2 ? 10 : 1);
                }
                times.add(minutes * 60_000 + seconds * 1000 + millis);
                textStart = matcher.end();
            }
            String text = raw.substring(textStart).trim();
            for (long time : times) {
                lines.add(new TimedLine(time, text));
            }
        }
        lines.sort(Comparator.comparingLong(TimedLine::timeMs));
        return lines;
    }

    // ---------------------------------------------------------------- HTTP

    private static @Nullable String get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(API + path))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() == 404) {
            return null;
        }
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("LRCLIB HTTP " + response.statusCode());
        }
        return response.body();
    }

    private static String params(String... pairs) {
        StringBuilder query = new StringBuilder();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (!query.isEmpty()) {
                query.append('&');
            }
            query.append(pairs[i]).append('=').append(URLEncoder.encode(pairs[i + 1], StandardCharsets.UTF_8));
        }
        return query.toString();
    }
}
