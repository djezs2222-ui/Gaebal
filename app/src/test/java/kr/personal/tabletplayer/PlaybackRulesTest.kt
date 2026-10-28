package kr.personal.tabletplayer

import org.junit.Assert.*
import org.junit.Test

class PlaybackRulesTest {
    private val s=listOf(PlaybackRules.Segment(1000,2000),PlaybackRules.Segment(),PlaybackRules.Segment(5000,7000),PlaybackRules.Segment(9000,10000))
    @Test fun seekClampsAtBothEnds() { assertEquals(0L,PlaybackRules.seekTarget(2000,-3000,10000)); assertEquals(10000L,PlaybackRules.seekTarget(9000,3000,10000)) }
    @Test fun missingAndReversedSegmentsAreInvalid() { assertFalse(PlaybackRules.Segment().valid()); assertFalse(PlaybackRules.Segment(2000,1000).valid()); assertFalse(PlaybackRules.Segment(1000,1000).valid()) }
    @Test fun segmentMustFitVideo() { assertFalse(PlaybackRules.Segment(1000,3000).valid(2000)) }
    @Test fun startsAtFirstCompleteSegment() { assertEquals(0 to 1000L,PlaybackRules.segmentTarget(s,-1,6000)) }
    @Test fun remainsInsideActiveSegment() { assertNull(PlaybackRules.segmentTarget(s,0,1500)) }
    @Test fun skipsEmptySegments() { assertEquals(2 to 5000L,PlaybackRules.segmentTarget(s,0,2000)) }
    @Test fun wrapsLastSegmentToFirst() { assertEquals(0 to 1000L,PlaybackRules.segmentTarget(s,3,10000)) }
    @Test fun manualSeekOutsideReturnsToSegment() { assertEquals(2 to 5000L,PlaybackRules.segmentTarget(s,2,1000)) }
    @Test fun emptySegmentsDoNotSeek() { assertNull(PlaybackRules.segmentTarget(List(4) { PlaybackRules.Segment() },-1,100)) }
    @Test fun stopsAtQueueEndWhenRepeatOff() { assertNull(PlaybackRules.next(2,3,0,false,emptyList())); assertEquals(1,PlaybackRules.next(0,3,0,false,emptyList())) }
    @Test fun repeatsCurrentBeforeShuffle() { assertEquals(1,PlaybackRules.next(1,3,1,true,listOf(0,2))) }
    @Test fun wrapsWholeQueue() { assertEquals(0,PlaybackRules.next(2,3,2,false,emptyList())) }
    @Test fun shuffleConsumesBagAndStops() { assertEquals(2,PlaybackRules.next(0,3,0,true,listOf(2,1))); assertNull(PlaybackRules.next(0,3,0,true,emptyList())) }
    @Test fun oneVideoWholeQueueRepeat() { assertEquals(0,PlaybackRules.next(0,1,2,true,emptyList())) }
    @Test fun emptyQueueNeverRepeats() { assertNull(PlaybackRules.next(0,0,1,true,emptyList())) }
}
