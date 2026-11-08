package com.viby.app

import com.viby.app.model.Track
import com.viby.app.model.TrackFilter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TrackFilterTest {
    private val done = Track("1", "a", "x", 1)
    private val waiting = Track("2", "b", "x", 1, downloadProgress = 0)
    private val failed = Track("3", "c", "x", 1, downloadProgress = 0, failed = "Ð’Ð¸Ð´ÐµÐ¾ ÑƒÐ´Ð°Ð»ÐµÐ½Ð¾")

    @Test
    fun filtersByDownloadState() {
        val all = listOf(done, waiting, failed)
        assertEquals(all, all.filter(TrackFilter.ALL::matches))
        assertEquals(listOf(waiting, failed), all.filter(TrackFilter.NOT_DOWNLOADED::matches))
        assertEquals(listOf(failed), all.filter(TrackFilter.FAILED::matches))
    }
}
