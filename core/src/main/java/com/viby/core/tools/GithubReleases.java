package com.viby.core.tools;

import org.jspecify.annotations.Nullable;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/** Reads the latest release of a GitHub repository through the public REST API. */
public final class GithubReleases {

    public record Asset(String name, URI url, @Nullable String sha256) {
    }

    public record Release(String tag, List<Asset> assets) {

        public @Nullable Asset asset(String name) {
            for (Asset asset : assets) {
                if (asset.name().equals(name)) {
                    return asset;
                }
            }
            return null;
        }
    }

    private final Http http;

    public GithubReleases(Http http) {
        this.http = http;
    }

    public Release latest(String repository) throws IOException, InterruptedException {
        String body = http.getString(URI.create(
                "https://api.github.com/repos/" + repository + "/releases/latest"));
        return parse(new JSONObject(body));
    }

    static Release parse(JSONObject json) {
        List<Asset> assets = new ArrayList<>();
        JSONArray items = json.optJSONArray("assets");
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                assets.add(new Asset(item.getString("name"),
                        URI.create(item.getString("browser_download_url")),
                        sha256FromDigest(item.optString("digest", null))));
            }
        }
        return new Release(json.optString("tag_name", ""), assets);
    }

    /** GitHub reports asset digests as {@code sha256:<hex>}. */
    static @Nullable String sha256FromDigest(@Nullable String digest) {
        if (digest == null || !digest.startsWith("sha256:")) {
            return null;
        }
        return digest.substring("sha256:".length());
    }
}
