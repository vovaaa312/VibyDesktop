package com.viby.core.update;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ReleaseVersionTest {

    @Test
    void newerPatchIsDetected() {
        assertTrue(ReleaseVersion.isNewer("v1.2.1", "1.2.0"));
    }

    @Test
    void fourthVersionComponentIsSupported() {
        assertTrue(ReleaseVersion.isNewer("v1.2.8.1", "1.2.8"));
        assertFalse(ReleaseVersion.isNewer("v1.2.8", "1.2.8.1"));
    }

    @Test
    void equalVersionsAreNotNewer() {
        assertFalse(ReleaseVersion.isNewer("v1.2.0", "1.2"));
    }

    @Test
    void olderVersionIsNotNewer() {
        assertFalse(ReleaseVersion.isNewer("v1.1.9", "1.2.0"));
    }

    @Test
    void malformedVersionIsRejected() {
        assertFalse(ReleaseVersion.isNewer("latest", "1.2.0"));
    }

    @Test
    void prefixesWhitespaceAndBuildSuffixesAreHandled() {
        assertTrue(ReleaseVersion.isNewer("  V2.0.0-beta+7 ", "1.9.9"));
        assertFalse(ReleaseVersion.isNewer("v1.2.0+15", "1.2"));
    }

    @Test
    void nullBlankNegativeAndOverflowVersionsAreRejected() {
        assertFalse(ReleaseVersion.isNewer(null, "1.0"));
        assertFalse(ReleaseVersion.isNewer("", "1.0"));
        assertFalse(ReleaseVersion.isNewer("-1", "1.0"));
        assertFalse(ReleaseVersion.isNewer("999999999999", "1.0"));
        assertFalse(ReleaseVersion.isNewer("1..2", "1.0"));
    }
}
