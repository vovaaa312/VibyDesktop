package com.viby.core.playback;

/** Public entry point to the package-private {@link PlaybackQueueOrder} ported from Android. */
public final class PlaybackQueueOrderAccess {

    private PlaybackQueueOrderAccess() {
    }

    public static int[] moveInsertedRangeAfterCurrent(int[] playbackOrder, int currentIndex,
                                                      int rangeStart, int rangeCount) {
        return PlaybackQueueOrder.moveInsertedRangeAfterCurrent(
                playbackOrder, currentIndex, rangeStart, rangeCount);
    }
}
