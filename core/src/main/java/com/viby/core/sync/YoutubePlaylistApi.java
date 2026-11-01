package com.viby.core.sync;

import org.jspecify.annotations.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Minimal blocking client for the official YouTube Data API v3. */
public final class YoutubePlaylistApi {

    private static final String API = "https://www.googleapis.com/youtube/v3/";
    private static final int TIMEOUT_MS = 20_000;

    public static final class OwnedPlaylist {
        public final String id;
        public final String title;
        public final String channelId;

        OwnedPlaylist(String id, String title, String channelId) {
            this.id = id;
            this.title = title;
            this.channelId = channelId;
        }
    }

    public static final class PlaylistItem {
        public final String id;
        public final String videoId;
        public final int position;

        PlaylistItem(String id, String videoId, int position) {
            this.id = id;
            this.videoId = videoId;
            this.position = position;
        }
    }

    public static final class ApiException extends IOException {
        public final int statusCode;
        @Nullable public final String reason;

        ApiException(int statusCode, String message, @Nullable String reason) {
            super(message);
            this.statusCode = statusCode;
            this.reason = reason;
        }
    }

    @Nullable
    public OwnedPlaylist findOwnedPlaylist(String accessToken, String playlistId)
            throws IOException, JSONException {
        String pageToken = null;
        do {
            String url = API + "playlists?part=id%2Csnippet&mine=true&maxResults=50"
                    + page(pageToken);
            JSONObject root = request("GET", url, accessToken, null);
            JSONArray items = root.optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.getJSONObject(i);
                    if (!playlistId.equals(item.optString("id"))) {
                        continue;
                    }
                    JSONObject snippet = item.optJSONObject("snippet");
                    return new OwnedPlaylist(playlistId,
                            snippet != null ? snippet.optString("title", playlistId)
                                    : playlistId,
                            snippet != null ? snippet.optString("channelId", "") : "");
                }
            }
            pageToken = emptyToNull(root.optString("nextPageToken", null));
        } while (pageToken != null);
        return null;
    }

    public List<PlaylistItem> listItems(String accessToken, String playlistId)
            throws IOException, JSONException {
        List<PlaylistItem> result = new ArrayList<>();
        String pageToken = null;
        do {
            String url = API + "playlistItems?part=id%2Csnippet&maxResults=50&playlistId="
                    + encode(playlistId) + page(pageToken);
            JSONObject root = request("GET", url, accessToken, null);
            JSONArray items = root.optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.getJSONObject(i);
                    JSONObject snippet = item.optJSONObject("snippet");
                    JSONObject resource = snippet != null
                            ? snippet.optJSONObject("resourceId") : null;
                    String itemId = item.optString("id", "");
                    String videoId = resource != null
                            ? resource.optString("videoId", "") : "";
                    if (!itemId.isEmpty() && !videoId.isEmpty()) {
                        result.add(new PlaylistItem(itemId, videoId,
                                snippet.optInt("position", result.size())));
                    }
                }
            }
            pageToken = emptyToNull(root.optString("nextPageToken", null));
        } while (pageToken != null);
        return result;
    }

    public record Channel(String id, String title, @Nullable String thumbnailUrl) {
    }

    public record AccountPlaylist(String id, String title, int itemCount, String privacy,
                                  @Nullable String thumbnailUrl) {
    }

    /** A playlist entry with the metadata needed for placeholder tracks. */
    public record PlaylistVideo(String videoId, String title, @Nullable String channel, int position) {
    }

    public Channel myChannel(String accessToken) throws IOException, JSONException {
        JSONObject root = request("GET", API + "channels?part=snippet&mine=true", accessToken, null);
        JSONArray items = root.optJSONArray("items");
        if (items == null || items.isEmpty()) {
            throw new ApiException(404, "У аккаунта нет канала YouTube", "channelNotFound");
        }
        JSONObject item = items.getJSONObject(0);
        JSONObject snippet = item.optJSONObject("snippet");
        return new Channel(item.optString("id"), snippet != null ? snippet.optString("title", "") : "",
                thumbnail(snippet));
    }

    /** Playlists owned by the signed-in channel, including private ones. */
    public List<AccountPlaylist> myPlaylists(String accessToken) throws IOException, JSONException {
        List<AccountPlaylist> result = new ArrayList<>();
        String pageToken = null;
        do {
            JSONObject root = request("GET", API + "playlists?part=snippet%2CcontentDetails%2Cstatus"
                    + "&mine=true&maxResults=50" + page(pageToken), accessToken, null);
            JSONArray items = root.optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.getJSONObject(i);
                    JSONObject snippet = item.optJSONObject("snippet");
                    JSONObject details = item.optJSONObject("contentDetails");
                    JSONObject status = item.optJSONObject("status");
                    result.add(new AccountPlaylist(item.optString("id"),
                            snippet != null ? snippet.optString("title", "") : "",
                            details != null ? details.optInt("itemCount", 0) : 0,
                            status != null ? status.optString("privacyStatus", "") : "",
                            thumbnail(snippet)));
                }
            }
            pageToken = emptyToNull(root.optString("nextPageToken", null));
        } while (pageToken != null);
        return result;
    }

    /** Videos of any playlist the account can see; deleted and private entries are skipped. */
    public List<PlaylistVideo> playlistVideos(String accessToken, String playlistId)
            throws IOException, JSONException {
        List<PlaylistVideo> result = new ArrayList<>();
        String pageToken = null;
        do {
            JSONObject root = request("GET", API + "playlistItems?part=snippet&maxResults=50&playlistId="
                    + encode(playlistId) + page(pageToken), accessToken, null);
            JSONArray items = root.optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject snippet = items.getJSONObject(i).optJSONObject("snippet");
                    JSONObject resource = snippet != null ? snippet.optJSONObject("resourceId") : null;
                    String videoId = resource != null ? resource.optString("videoId", "") : "";
                    String title = snippet != null ? snippet.optString("title", "") : "";
                    if (videoId.isEmpty() || title.equals("Private video") || title.equals("Deleted video")) {
                        continue;
                    }
                    result.add(new PlaylistVideo(videoId, title,
                            emptyToNull(snippet.optString("videoOwnerChannelTitle", null)),
                            snippet.optInt("position", result.size())));
                }
            }
            pageToken = emptyToNull(root.optString("nextPageToken", null));
        } while (pageToken != null);
        return result;
    }

    /** Video durations in milliseconds, looked up 50 ids per request. */
    public java.util.Map<String, Long> durations(String accessToken, List<String> videoIds)
            throws IOException, JSONException {
        java.util.Map<String, Long> result = new java.util.HashMap<>();
        for (int from = 0; from < videoIds.size(); from += 50) {
            String ids = String.join(",", videoIds.subList(from, Math.min(from + 50, videoIds.size())));
            JSONObject root = request("GET", API + "videos?part=contentDetails&id=" + encode(ids),
                    accessToken, null);
            JSONArray items = root.optJSONArray("items");
            if (items == null) {
                continue;
            }
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                JSONObject details = item.optJSONObject("contentDetails");
                try {
                    result.put(item.optString("id"), java.time.Duration.parse(
                            details != null ? details.optString("duration", "PT0S") : "PT0S").toMillis());
                } catch (java.time.format.DateTimeParseException ignored) {
                    // live streams report P0D; leave the duration unknown
                }
            }
        }
        return result;
    }

    private static @Nullable String thumbnail(@Nullable JSONObject snippet) {
        JSONObject thumbnails = snippet != null ? snippet.optJSONObject("thumbnails") : null;
        if (thumbnails == null) {
            return null;
        }
        for (String size : List.of("medium", "high", "default")) {
            JSONObject image = thumbnails.optJSONObject(size);
            if (image != null && !image.optString("url", "").isEmpty()) {
                return image.optString("url");
            }
        }
        return null;
    }

    public PlaylistItem insertAtStart(String accessToken, String playlistId,
                                      String videoId)
            throws IOException, JSONException {
        return insertAtPosition(accessToken, playlistId, videoId, 0);
    }

    public PlaylistItem insertAtPosition(String accessToken, String playlistId,
                                         String videoId, int position)
            throws IOException, JSONException {
        if (position < 0) {
            throw new IllegalArgumentException("Playlist position cannot be negative");
        }
        try {
            return insert(accessToken, playlistId, videoId, position);
        } catch (ApiException error) {
            if (error.statusCode != 400
                    || !"manualSortRequired".equals(error.reason)) {
                throw error;
            }
            // Date/popularity-sorted playlists reject an explicit position.
            // Let YouTube apply their configured order and use the returned position.
            return insert(accessToken, playlistId, videoId, null);
        }
    }

    public OwnedPlaylist createPrivatePlaylist(String accessToken, String title,
                                                String description)
            throws IOException, JSONException {
        JSONObject snippet = new JSONObject()
                .put("title", title)
                .put("description", description);
        JSONObject status = new JSONObject().put("privacyStatus", "private");
        JSONObject body = new JSONObject()
                .put("snippet", snippet)
                .put("status", status);
        JSONObject response = request("POST",
                API + "playlists?part=snippet%2Cstatus", accessToken, body.toString());
        String playlistId = response.optString("id", "");
        if (playlistId.isEmpty()) {
            throw new IOException("YouTube returned an empty playlist id");
        }
        JSONObject returnedSnippet = response.optJSONObject("snippet");
        return new OwnedPlaylist(playlistId,
                returnedSnippet != null
                        ? returnedSnippet.optString("title", title) : title,
                returnedSnippet != null
                        ? returnedSnippet.optString("channelId", "") : "");
    }

    private PlaylistItem insert(String accessToken, String playlistId,
                                String videoId, @Nullable Integer position)
            throws IOException, JSONException {
        JSONObject resourceId = new JSONObject()
                .put("kind", "youtube#video")
                .put("videoId", videoId);
        JSONObject snippet = new JSONObject()
                .put("playlistId", playlistId)
                .put("resourceId", resourceId);
        if (position != null) {
            snippet.put("position", position);
        }
        JSONObject body = new JSONObject().put("snippet", snippet);
        JSONObject response = request("POST", API + "playlistItems?part=id%2Csnippet",
                accessToken, body.toString());
        JSONObject returnedSnippet = response.optJSONObject("snippet");
        String itemId = response.optString("id", "");
        if (itemId.isEmpty()) {
            throw new IOException("YouTube returned an empty playlist item id");
        }
        return new PlaylistItem(itemId, videoId,
                returnedSnippet != null ? returnedSnippet.optInt("position", 0) : 0);
    }

    public void deleteItem(String accessToken, String playlistItemId)
            throws IOException, JSONException {
        request("DELETE", API + "playlistItems?id=" + encode(playlistItemId),
                accessToken, null);
    }

    private static JSONObject request(String method, String url, String accessToken,
                                      @Nullable String body)
            throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection) java.net.URI.create(url).toURL().openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setRequestProperty("Authorization", "Bearer " + accessToken);
        connection.setRequestProperty("Accept", "application/json");
        if (body != null) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
        }

        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300
                ? connection.getInputStream() : connection.getErrorStream();
        String response = read(stream);
        connection.disconnect();
        if (status < 200 || status >= 300) {
            throw new ApiException(status, apiError(response, status),
                    apiErrorReason(response));
        }
        return response.isEmpty() ? new JSONObject() : new JSONObject(response);
    }

    private static String read(@Nullable InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line);
            }
        }
        return result.toString();
    }

    private static String apiError(String response, int status) {
        try {
            JSONObject error = new JSONObject(response).optJSONObject("error");
            if (error != null) {
                String message = error.optString("message", "");
                if (!message.isEmpty()) {
                    return message;
                }
            }
        } catch (JSONException ignored) {
        }
        return "YouTube API error " + status;
    }

    @Nullable
    private static String apiErrorReason(String response) {
        try {
            JSONObject error = new JSONObject(response).optJSONObject("error");
            JSONArray errors = error != null ? error.optJSONArray("errors") : null;
            if (errors != null && errors.length() > 0) {
                JSONObject detail = errors.optJSONObject(0);
                return detail != null
                        ? emptyToNull(detail.optString("reason", null)) : null;
            }
        } catch (JSONException ignored) {
        }
        return null;
    }

    private static String page(@Nullable String token) throws IOException {
        return token == null ? "" : "&pageToken=" + encode(token);
    }

    private static String encode(String value) throws IOException {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
    }

    @Nullable
    private static String emptyToNull(@Nullable String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
