package kr.personal.tabletplayer

/** Pure playback decisions, shared by keyboard, gestures and the playback clock. */
object PlaybackRules {
    data class Segment(val start:Long=-1,val end:Long=-1) {
        fun valid(length:Long=Long.MAX_VALUE)=start>=0 && end>start && end<=length
    }
    fun seekTarget(time:Long,delta:Long,length:Long):Long=(time+delta).coerceIn(0,length.coerceAtLeast(0))
    fun segmentTarget(segments:List<Segment>,active:Int,time:Long):Pair<Int,Long>? {
        val valid=segments.indices.filter { segments[it].valid() }
        if(valid.isEmpty()) return null
        val slot=active.takeIf { it in valid } ?: valid.first()
        val segment=segments[slot]
        return when {
            active!=slot || time<segment.start -> slot to segment.start
            time>=segment.end -> valid[(valid.indexOf(slot)+1)%valid.size].let { it to segments[it].start }
            else -> null
        }
    }
    fun next(index:Int,size:Int,repeat:Int,shuffle:Boolean,remaining:List<Int>):Int? {
        if(size<=0) return null
        if(repeat==1) return index
        if(shuffle) return remaining.firstOrNull() ?: if(repeat==2) (0 until size).firstOrNull { it!=index } ?: index else null
        return if(index+1<size) index+1 else if(repeat==2) 0 else null
    }
}
