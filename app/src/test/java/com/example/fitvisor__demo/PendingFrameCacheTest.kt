package com.example.fitvisor__demo

import com.example.fitvisor__demo.pose.PendingFrameCache
import org.junit.Assert.*
import org.junit.Test

class PendingFrameCacheTest {

    @Test fun putThenTakeReturnsTheSameValue() {
        val cache = PendingFrameCache<String>(windowMillis = 1_000)
        cache.put(100L, "frame-100")
        assertEquals("frame-100", cache.take(100L))
    }

    @Test fun takeRemovesTheEntrySoItCannotBeTakenTwice() {
        val cache = PendingFrameCache<String>(windowMillis = 1_000)
        cache.put(100L, "frame-100")
        cache.take(100L)
        assertNull(cache.take(100L))
    }

    @Test fun takeOfUnknownTimestampReturnsNull() {
        val cache = PendingFrameCache<String>(windowMillis = 1_000)
        assertNull(cache.take(999L))
    }

    @Test fun multipleInFlightEntriesAreKeptIndependently() {
        val cache = PendingFrameCache<String>(windowMillis = 1_000)
        cache.put(100L, "a")
        cache.put(200L, "b")
        assertEquals("b", cache.take(200L))
        assertEquals("a", cache.take(100L))
    }

    @Test fun entriesOlderThanTheWindowAreEvictedOnPut() {
        val cache = PendingFrameCache<String>(windowMillis = 500)
        cache.put(1_000L, "stale")
        // 1_000 is older than 1_600 - 500 = 1_100, so it must be evicted.
        cache.put(1_600L, "fresh")
        assertNull(cache.take(1_000L))
        assertEquals("fresh", cache.take(1_600L))
    }

    @Test fun entriesWithinTheWindowSurvive() {
        val cache = PendingFrameCache<String>(windowMillis = 500)
        cache.put(1_000L, "recent")
        cache.put(1_400L, "fresh") // 1_000 >= 1_400 - 500 = 900, so it survives.
        assertEquals("recent", cache.take(1_000L))
    }

    @Test fun removeDropsAnUntakenEntry() {
        val cache = PendingFrameCache<String>(windowMillis = 1_000)
        cache.put(100L, "a")
        cache.remove(100L)
        assertNull(cache.take(100L))
    }

    @Test fun clearDropsEverything() {
        val cache = PendingFrameCache<String>(windowMillis = 1_000)
        cache.put(100L, "a")
        cache.put(200L, "b")
        cache.clear()
        assertEquals(0, cache.size)
        assertNull(cache.take(100L))
        assertNull(cache.take(200L))
    }
}
