package com.viby.core.lyrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONArray;
import org.junit.jupiter.api.Test;

import java.util.List;

class LrclibLyricsTest {

    @Test
    void lrcLinesBecomeTimedLines() {
        List<TimedLine> lines = LrclibLyrics.parseLrc("""
                [00:44.91] Скажи, кукушка, пропой
                [01:06.38][02:10.5] Hast viel geweint
                [ar: Kino]
                [03:00.123]
                """);
        assertEquals(List.of(
                new TimedLine(44_910, "Скажи, кукушка, пропой"),
                new TimedLine(66_380, "Hast viel geweint"),
                new TimedLine(130_500, "Hast viel geweint"),
                new TimedLine(180_123, "")), lines);
    }

    @Test
    void youtubeTitlesAreCleanedForTheSearch() {
        assertEquals(new LrclibLyrics.Query("Улица Восток", "Запомню", 186),
                LrclibLyrics.query("Улица Восток", "Улица Восток - Запомню (Directed by Max Balter x Mishka)", 186_000));
        assertEquals(new LrclibLyrics.Query("Kino", "Кукушка", 400),
                LrclibLyrics.query("Kino - Topic", "Кукушка", 399_600));
        // a label uploaded it: the artist comes from the title
        assertEquals(new LrclibLyrics.Query("Armin van Buuren", "In And Out Of Love", 300),
                LrclibLyrics.query("Armada Music TV", "Armin van Buuren feat. Sharon den Adel - In And Out Of Love [Official Video]", 300_000));
        assertEquals(new LrclibLyrics.Query("Rammstein", "Ich Will", 217),
                LrclibLyrics.query("Rammstein Official", "Rammstein - Ich Will (Official Video)", 217_000));
        // Topic titles are already clean
        assertEquals(new LrclibLyrics.Query("Eminem", "Lose Yourself - Remastered", 320),
                LrclibLyrics.query("Eminem - Topic", "Lose Yourself - Remastered", 320_000));
    }

    @Test
    void searchPrefersSyncedLyricsOfACloseLength() {
        JSONArray results = new JSONArray("""
                [
                  {"artistName":"Король и Шут","trackName":"Кукла колдуна (Из сериала)","duration":154,"plainLyrics":"short","syncedLyrics":"[00:01.00] short"},
                  {"artistName":"Король и Шут","trackName":"Кукла колдуна","duration":202,"plainLyrics":"plain","syncedLyrics":""},
                  {"artistName":"Король и Шут","trackName":"Кукла колдуна (Из сериала «Король и Шут»)","duration":206,"plainLyrics":"full","syncedLyrics":"[00:10.00] Тёмный, мрачный коридор"},
                  {"artistName":"Someone","trackName":"Other song","duration":204,"plainLyrics":"x","syncedLyrics":"[00:01.00] x"}
                ]
                """);
        LrclibLyrics.Result result = LrclibLyrics.bestMatch(results, new LrclibLyrics.Query("Король и Шут", "Кукла колдуна", 204));
        assertEquals(List.of(new TimedLine(10_000, "Тёмный, мрачный коридор")), result.synced());
        assertNull(LrclibLyrics.bestMatch(results, new LrclibLyrics.Query("Король и Шут", "Другая песня", 204)));
        // another spelling of the artist: accepted only for the same title and length
        JSONArray latin = new JSONArray("[{\"artistName\":\"Ulitsa Vostok\",\"trackName\":\"Запомню\",\"duration\":187,\"plainLyrics\":\"x\",\"syncedLyrics\":\"[00:42.39] Позабыл всё, что берег\"}]");
        assertEquals(1, LrclibLyrics.bestMatch(latin, new LrclibLyrics.Query("Улица Восток", "Запомню", 186)).synced().size());
        assertNull(LrclibLyrics.bestMatch(latin, new LrclibLyrics.Query("Улица Восток", "Запомню", 180)));
    }

    @Test
    void subtitlesKeepTheirTimes() {
        String payload = "{\"events\":[{\"tStartMs\":1200,\"segs\":[{\"utf8\":\"first line\"}]},"
                + "{\"tStartMs\":3400,\"segs\":[{\"utf8\":\"second\\nthird\"}]},"
                + "{\"tStartMs\":5000,\"segs\":[{\"utf8\":\"third\"}]}]}";
        List<TimedLine> lines = YoutubeLyricsRepository.parseJson3Timed(payload);
        assertEquals(List.of(new TimedLine(1200, "first line"), new TimedLine(3400, "second"),
                new TimedLine(3400, "third")), lines);
        assertTrue(YoutubeLyricsRepository.parseJson3Timed("").isEmpty());
    }
}
