package com.viby.core.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.viby.core.data.Track;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

class YoutubePlaylistSyncTest {

    @Test
    void recognizesOriginalPlaylistButRejectsUnsavedRadio() {
        com.viby.core.data.PlaylistSource original =
                new com.viby.core.data.PlaylistSource();
        original.sourceUrl = "https://www.youtube.com/playlist?list=PLowned";
        com.viby.core.data.PlaylistSource radio =
                new com.viby.core.data.PlaylistSource();
        radio.sourceUrl = "https://www.youtube.com/watch?v=seed&list=RDseed";

        org.junit.jupiter.api.Assertions.assertTrue(
                YoutubePlaylistSync.hasOriginalYoutubePlaylist(original));
        org.junit.jupiter.api.Assertions.assertFalse(
                YoutubePlaylistSync.hasOriginalYoutubePlaylist(radio));
    }

    @Test
    void missingYoutubeTracksExcludeRemoteAndDeviceOnlyEntries() {
        Track missing = track(1, "missing", 2);
        Track linked = track(2, "linked", 1);
        linked.youtubePlaylistItemId = "remote-item";
        Track deviceOnly = track(3, null, 0);

        List<Track> result = YoutubePlaylistSync.missingYoutubeTracks(
                new ArrayList<>(Arrays.asList(missing, linked, deviceOnly)), null);

        assertEquals(1, result.size());
        assertEquals(1, result.get(0).id);
        org.junit.jupiter.api.Assertions.assertTrue(YoutubePlaylistSync.hasMissingYoutubeTracks(result));
    }

    @Test
    void locallyAddedTracksRequireYoutubeIdAndNoSourcePosition() {
        Track imported = track(1, "imported", 0);
        imported.youtubePosition = 0;
        Track copied = track(2, "copied", 1);
        Track deviceOnly = track(3, null, 2);

        org.junit.jupiter.api.Assertions.assertTrue(
                YoutubePlaylistSync.hasLocallyAddedYoutubeTracks(
                        Arrays.asList(imported, copied, deviceOnly)));
        copied.youtubePosition = 1;
        org.junit.jupiter.api.Assertions.assertFalse(
                YoutubePlaylistSync.hasLocallyAddedYoutubeTracks(
                        Arrays.asList(imported, copied, deviceOnly)));
    }

    @Test
    void requestedMissingTracksRespectOccurrenceCountsAndLocalOrder() {
        Track firstDuplicate = track(1, "same", 5);
        Track secondDuplicate = track(2, "same", 2);
        Track other = track(3, "other", 1);

        List<Track> result = YoutubePlaylistSync.missingYoutubeTracks(
                new ArrayList<>(Arrays.asList(firstDuplicate, secondDuplicate, other)),
                Arrays.asList("same"));

        assertEquals(1, result.size());
        assertEquals(2, result.get(0).id);
    }

    @Test
    void linksDuplicateVideosToDistinctPlaylistItems() {
        Track first = track(1, "same", 0);
        Track second = track(2, "same", 1);
        List<Track> tracks = new ArrayList<>(Arrays.asList(first, second));
        List<YoutubePlaylistApi.PlaylistItem> remote = Arrays.asList(
                item("item-new", "same", 0),
                item("item-old", "same", 1));

        int linked = YoutubePlaylistSync.linkTracks(tracks, remote, true);

        assertEquals(2, linked);
        assertEquals("item-new", tracks.get(0).youtubePlaylistItemId);
        assertEquals("item-old", tracks.get(1).youtubePlaylistItemId);
    }

    @Test
    void preservesExactOccurrenceAcrossRemoteReordering() {
        Track older = track(1, "same", 0);
        older.youtubePlaylistItemId = "item-old";
        Track newer = track(2, "same", 1);
        newer.youtubePlaylistItemId = "item-new";
        List<Track> tracks = new ArrayList<>(Arrays.asList(older, newer));
        List<YoutubePlaylistApi.PlaylistItem> remote = Arrays.asList(
                item("item-new", "same", 0),
                item("item-old", "same", 1));

        YoutubePlaylistSync.linkTracks(tracks, remote, true);

        assertEquals(2, tracks.get(0).id);
        assertEquals("item-new", tracks.get(0).youtubePlaylistItemId);
        assertEquals(1, tracks.get(1).id);
        assertEquals("item-old", tracks.get(1).youtubePlaylistItemId);
    }

    @Test
    void leavesLocalOnlyTrackUnlinked() {
        Track remoteTrack = track(1, "remote", 0);
        Track localTrack = track(2, "local", 1);
        List<Track> tracks = new ArrayList<>(Arrays.asList(remoteTrack, localTrack));

        YoutubePlaylistSync.linkTracks(tracks,
                Arrays.asList(item("item", "remote", 0)), true);

        assertEquals("item", tracks.get(0).youtubePlaylistItemId);
        assertNull(tracks.get(1).youtubePlaylistItemId);
        assertNull(tracks.get(1).youtubePosition);
    }

    @Test
    void staleOccurrenceIdIsReplacedByMatchingVideoOccurrence() {
        Track track = track(1, "video", 0);
        track.youtubePlaylistItemId = "stale-item";
        List<Track> tracks = new ArrayList<>(Arrays.asList(track));

        YoutubePlaylistSync.linkTracks(tracks,
                Arrays.asList(item("new-item", "video", 4)), true);

        assertEquals("new-item", track.youtubePlaylistItemId);
        assertEquals(Integer.valueOf(4), track.youtubePosition);
    }

    @Test
    void missingRemoteOccurrenceClearsOldLinkButRemembersItCameFromYoutube() {
        Track track = track(1, "video", 0);
        track.youtubePlaylistItemId = "old-item";
        track.youtubePosition = 9;

        int linked = YoutubePlaylistSync.linkTracks(
                new ArrayList<>(Arrays.asList(track)), new ArrayList<>(), true);

        assertEquals(0, linked);
        assertNull(track.youtubePlaylistItemId);
        assertNull(track.youtubePosition);
    }

    @Test
    void linkingWithoutReorderPreservesLocalListAndPositions() {
        Track first = track(1, "first", 10);
        Track second = track(2, "second", 20);
        List<Track> tracks = new ArrayList<>(Arrays.asList(first, second));

        YoutubePlaylistSync.linkTracks(tracks, Arrays.asList(
                item("second-item", "second", 0),
                item("first-item", "first", 1)), false);

        assertEquals(1, tracks.get(0).id);
        assertEquals(2, tracks.get(1).id);
        assertEquals(10, tracks.get(0).position);
        assertEquals(20, tracks.get(1).position);
    }

    @Test
    void linkingWithReorderMovesCopiedTrackToRemotePosition() {
        Track existing = track(1, "existing", 0);
        Track copiedAtEnd = track(2, "copied", 1);
        List<Track> tracks = new ArrayList<>(
                Arrays.asList(existing, copiedAtEnd));

        YoutubePlaylistSync.linkTracks(tracks, Arrays.asList(
                item("copied-item", "copied", 0),
                item("existing-item", "existing", 1)), true);

        assertEquals(2, tracks.get(0).id);
        assertEquals(0, tracks.get(0).position);
        assertEquals(1, tracks.get(1).id);
        assertEquals(1, tracks.get(1).position);
    }

    /** The send-to-playlist bug: a lagging list must not push the new track to the bottom. */
    @Test
    void insertedItemMissingFromLaggingListStaysWhereItWasInserted() {
        Track sent = track(3, "sent", 0);
        Track first = track(1, "first", 1);
        Track second = track(2, "second", 2);
        List<Track> tracks = new ArrayList<>(Arrays.asList(sent, first, second));
        List<YoutubePlaylistApi.PlaylistItem> lagging = Arrays.asList(
                item("first-item", "first", 0), item("second-item", "second", 1));

        YoutubePlaylistSync.linkTracks(tracks, YoutubePlaylistSync.withInserted(
                lagging, List.of(item("sent-item", "sent", 0))), true);

        assertEquals(Arrays.asList(sent, first, second), tracks);
        assertEquals("sent-item", sent.youtubePlaylistItemId);
        assertEquals(Integer.valueOf(0), sent.youtubePosition);
        assertEquals(Integer.valueOf(1), first.youtubePosition);
        assertEquals(Integer.valueOf(2), second.youtubePosition);
        assertEquals(0, sent.position);
    }

    @Test
    void insertedItemAlreadyListedIsNotDuplicated() {
        List<YoutubePlaylistApi.PlaylistItem> listed = Arrays.asList(
                item("sent-item", "sent", 0), item("first-item", "first", 1));

        List<YoutubePlaylistApi.PlaylistItem> merged = YoutubePlaylistSync.withInserted(
                listed, List.of(item("sent-item", "sent", 0)));

        assertEquals(2, merged.size());
        assertEquals(1, merged.get(1).position);
    }

    private static Track track(long id, String videoId, int position) {
        Track track = new Track();
        track.id = id;
        track.videoId = videoId;
        track.position = position;
        return track;
    }

    private static YoutubePlaylistApi.PlaylistItem item(
            String id, String videoId, int position) {
        return new YoutubePlaylistApi.PlaylistItem(id, videoId, position);
    }
}
