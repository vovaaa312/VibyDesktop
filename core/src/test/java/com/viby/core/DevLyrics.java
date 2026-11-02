package com.viby.core;

import com.viby.core.lyrics.YoutubeLyricsRepository;

/** Manual check: ./gradlew :core:devLyrics --args="<videoId> [<videoId>...]" */
public final class DevLyrics {

    public static void main(String[] args) throws Exception {
        for (String videoId : args) {
            try {
                YoutubeLyricsRepository.Lyrics lyrics = YoutubeLyricsRepository.load(null, videoId, null);
                String text = lyrics.text.replace('\n', '/');
                System.out.println(videoId + " -> " + lyrics.source + " (" + lyrics.detail + "): "
                        + text.substring(0, Math.min(120, text.length())));
            } catch (Exception e) {
                System.out.println(videoId + " -> " + e);
            }
        }
    }
}
