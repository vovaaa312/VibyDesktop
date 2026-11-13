package com.viby.core.lyrics;

/** A lyrics line and the moment it is sung, from the start of the track. Empty text = a pause. */
public record TimedLine(long timeMs, String text) {
}
