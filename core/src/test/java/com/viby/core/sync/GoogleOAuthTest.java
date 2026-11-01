package com.viby.core.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

class GoogleOAuthTest {

    @TempDir
    Path temp;

    @Test
    void pkceChallengeIsBase64UrlSha256WithoutPadding() {
        // reference value computed independently with Node: sha256(verifier) as base64url
        assertEquals("6se8-n-g2eL-rnS-PEkMqzt_Jsj-aoYZrLbeH1D_tuU",
                GoogleOAuth.challenge("dBjftJeZ4CVP-mJ92K9yt1Cu8V3lkb4m3ToK4yRvKYw"));
    }

    @Test
    void parsesLoopbackCallback() {
        Map<String, String> query = GoogleOAuth.parseQuery("state=abc&code=4%2F0Ab_c&scope=x");
        assertEquals("4/0Ab_c", query.get("code"));
        assertEquals("abc", query.get("state"));
        assertTrue(GoogleOAuth.parseQuery(null).isEmpty());
    }

    @Test
    void acceptsOnlyDesktopClients() {
        GoogleOAuth.ClientConfig config = GoogleOAuth.ClientConfig.parse(
                "{\"installed\":{\"client_id\":\"id\",\"client_secret\":\"s\"}}");
        assertEquals("https://oauth2.googleapis.com/token", config.tokenUri());
        assertThrows(IllegalArgumentException.class,
                () -> GoogleOAuth.ClientConfig.parse("{\"web\":{\"client_id\":\"id\"}}"));
    }

    @Test
    void unconfiguredClientIsSignedOutAndRefusesTokens() {
        GoogleOAuth oauth = new GoogleOAuth(null, temp.resolve("token.json"));
        assertFalse(oauth.isConfigured());
        assertFalse(oauth.isSignedIn());
        assertThrows(IOException.class, oauth::accessToken);
    }
}
