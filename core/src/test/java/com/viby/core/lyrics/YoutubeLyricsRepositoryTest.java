package com.viby.core.lyrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class YoutubeLyricsRepositoryTest {

    @Test
    void emptyCaptionResponseMeansNoSubtitles() throws Exception {
        assertEquals("", YoutubeLyricsRepository.parseJson3(""));
        assertEquals("", YoutubeLyricsRepository.parseJson3("<html>blocked</html>"));
    }

    @Test
    void json3EventsBecomeLines() throws Exception {
        String payload = "{\"events\":[{\"segs\":[{\"utf8\":\"first line\"}]},{\"segs\":[{\"utf8\":\"second\"}]}]}";
        assertEquals("first line\nsecond", YoutubeLyricsRepository.parseJson3(payload));
    }
}
