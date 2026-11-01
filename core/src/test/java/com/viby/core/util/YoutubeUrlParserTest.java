package com.viby.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class YoutubeUrlParserTest {

    @Test
    void extractsCommonYoutubeVideoUrls() {
        assertEquals("abcdefghijk", YoutubeUrlParser.videoId(
                "https://www.youtube.com/watch?v=abcdefghijk&list=PL123"));
        assertEquals("abcdefghijk", YoutubeUrlParser.videoId(
                "https://youtu.be/abcdefghijk?si=share"));
        assertEquals("abcdefghijk", YoutubeUrlParser.videoId(
                "https://music.youtube.com/watch?v=abcdefghijk"));
        assertEquals("abcdefghijk", YoutubeUrlParser.videoId(
                "https://youtube.com/shorts/abcdefghijk"));
        assertEquals("abcdefghijk", YoutubeUrlParser.videoId(
                "https://www.youtube.com/embed/abcdefghijk"));
        assertEquals("abcdefghijk", YoutubeUrlParser.videoId(
                "https://youtube.com/live/abcdefghijk?feature=share"));
    }

    @Test
    void rejectsNonYoutubeAndPlaylistOnlyUrls() {
        assertNull(YoutubeUrlParser.videoId("https://example.com/watch?v=abcdefghijk"));
        assertNull(YoutubeUrlParser.videoId(
                "https://www.youtube.com/playlist?list=PL123"));
        assertNull(YoutubeUrlParser.videoId(null));
        assertNull(YoutubeUrlParser.videoId("not a url"));
        assertNull(YoutubeUrlParser.videoId("https://youtube.com.evil.test/watch?v=abcdefghijk"));
    }

    @Test
    void extractsPlaylistIdsFromYoutubeUrls() {
        assertEquals("PL123", YoutubeUrlParser.playlistId(
                "https://www.youtube.com/playlist?list=PL123"));
        assertEquals("PL_MUSIC", YoutubeUrlParser.playlistId(
                "https://music.youtube.com/watch?v=abcdefghijk&list=PL_MUSIC"));
        assertNull(YoutubeUrlParser.playlistId(
                "https://example.com/playlist?list=PL123"));
        assertNull(YoutubeUrlParser.playlistId(
                "https://www.youtube.com/watch?v=abcdefghijk"));
        assertEquals("PL value", YoutubeUrlParser.playlistId(
                "https://youtube.com/playlist?list=PL%20value"));
    }

    @Test
    void buildsAndRecognizesTrackRadioUrls() {
        String radio = YoutubeUrlParser.radioMixUrl("abcdefghijk");

        assertEquals("https://www.youtube.com/watch?v=abcdefghijk"
                + "&list=RDabcdefghijk", radio);
        assertTrue(YoutubeUrlParser.isRadioMixUrl(radio));
        assertTrue(YoutubeUrlParser.isRadioPlaylistId("RDabcdefghijk"));
        assertFalse(YoutubeUrlParser.isRadioMixUrl(
                "https://www.youtube.com/playlist?list=PL123"));
        assertFalse(YoutubeUrlParser.isRadioPlaylistId(null));
    }

    @Test
    void radioUrlRequiresVideoId() {
        assertThrows(IllegalArgumentException.class, () -> YoutubeUrlParser.radioMixUrl("  "));
    }
}
