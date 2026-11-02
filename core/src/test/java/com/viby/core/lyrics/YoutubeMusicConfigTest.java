package com.viby.core.lyrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class YoutubeMusicConfigTest {

    @Test
    void extractsApiKeyFromCompactAndSpacedConfiguration() {
        assertEquals("example_web_client_token_12345",
                YoutubeMusicConfig.extractApiKey(
                        "{\"INNERTUBE_API_KEY\":\"example_web_client_token_12345\"}"));
        assertEquals("another-client-token_67890",
                YoutubeMusicConfig.extractApiKey(
                        "{\"INNERTUBE_API_KEY\" : \"another-client-token_67890\"}"));
    }

    @Test
    void rejectsMissingOrUnsafeConfigurationValues() {
        assertNull(YoutubeMusicConfig.extractApiKey("{}"));
        assertNull(YoutubeMusicConfig.extractApiKey(
                "{\"INNERTUBE_API_KEY\":\"short\"}"));
        assertNull(YoutubeMusicConfig.extractApiKey(
                "{\"INNERTUBE_API_KEY\":\"unsafe value with spaces\"}"));
    }
}
