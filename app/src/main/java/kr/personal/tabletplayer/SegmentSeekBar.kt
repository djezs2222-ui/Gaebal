package kr.personal.tabletplayer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.widget.SeekBar

class SegmentSeekBar(context:Context):SeekBar(context) {
    var segments:List<PlaybackRules.Segment> = emptyList()
    var duration=0L
    var active=-1
    companion object { val COLORS=intArrayOf(0xffff5252.toInt(),0xff448aff.toInt(),0xffffd740.toInt(),0xff69f0ae.toInt()) }
    private val marker=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        if(duration<=0) return
        val width=(width-paddingLeft-paddingRight).toFloat()
        val top=height/2f+6*resources.displayMetrics.density
        segments.forEachIndexed { n,s ->
            marker.color=COLORS[n]; marker.alpha=if(n==active) 255 else 170
            fun x(t:Long)=paddingLeft+width*(t.toDouble()/duration).coerceIn(0.0,1.0).toFloat()
            if(s.valid(duration)) canvas.drawRect(x(s.start),top,x(s.end),top+4*resources.displayMetrics.density,marker)
            if(s.start>=0) canvas.drawRect(x(s.start)-2,top-8,x(s.start)+2,top+6,marker)
            if(s.end>=0) canvas.drawRect(x(s.end)-2,top-8,x(s.end)+2,top+6,marker)
        }
    }
}
