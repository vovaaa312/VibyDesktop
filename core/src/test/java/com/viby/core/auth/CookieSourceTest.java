package com.viby.core.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.viby.core.ytdlp.YtDlpRequest;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

class CookieSourceTest {

    @Test
    void appliesBrowserWithOptionalProfile() {
        YtDlpRequest plain = new YtDlpRequest("url");
        CookieSource.browser("firefox", " ").apply(plain);
        assertEquals("firefox", plain.getOption("--cookies-from-browser"));

        YtDlpRequest profile = new YtDlpRequest("url");
        CookieSource.browser("chrome", "Profile 1").apply(profile);
        assertEquals("chrome:Profile 1", profile.getOption("--cookies-from-browser"));
    }

    @Test
    void appliesCookieFileAndNothingForNone() {
        YtDlpRequest file = new YtDlpRequest("url");
        CookieSource.file(Path.of("cookies.txt")).apply(file);
        assertEquals("cookies.txt", file.getOption("--cookies"));

        YtDlpRequest none = new YtDlpRequest("url");
        CookieSource.none().apply(none);
        assertEquals(1, none.buildCommand().size());
        assertFalse(CookieSource.none().isConfigured());
    }

    @Test
    void roundTripsThroughJsonAndToleratesGarbage() {
        CookieSource source = CookieSource.browser("edge", "Default");
        assertEquals(source, CookieSource.fromJson(source.toJson()));
        CookieSource file = CookieSource.file(Path.of("a", "b.txt"));
        assertEquals(file, CookieSource.fromJson(file.toJson()));
        assertEquals(CookieSource.none(), CookieSource.fromJson(null));
        assertEquals(CookieSource.none(), CookieSource.fromJson(
                new JSONObject().put("type", "BROWSER").put("browser", "netscape")));
        assertNull(CookieSource.none().browser());
        assertThrows(IllegalArgumentException.class, () -> CookieSource.browser("ie", null));
    }
}
