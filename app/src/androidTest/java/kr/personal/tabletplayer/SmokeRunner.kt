package kr.personal.tabletplayer

import android.app.*
import android.content.Intent
import android.media.*
import android.os.*
import android.view.*
import org.videolan.libvlc.MediaPlayer
import java.io.File

/** No external media, account or device files are needed. Generates its own AVC test clip. */
class SmokeRunner: Instrumentation() {
    override fun onCreate(arguments:Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result=Bundle()
        Thread {
            var activity:Activity?=null
            try {
                targetContext.getSharedPreferences("PlayerActivity",0).edit().clear().commit()
                val clip=File(targetContext.filesDir,"smoke.mp4"); encode(clip)
                sendStatus(1,Bundle().apply { putString("stream","Generated AVC clip; testing private HTTP range bridge.\n") })
                bridgeChecks(clip)
                fun open():Activity = startActivitySync(Intent(targetContext,PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("uri",android.net.Uri.fromFile(clip).toString()))
                activity=open(); waitForIdleSync(); await("Player window focus") { activity!!.hasWindowFocus() }
                sendStatus(1,Bundle().apply { putString("stream","Player window focused; waiting for VLC playback.\n") })
                fun field(name:String):Any=PlayerActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.get(activity)
                var player=field("player") as MediaPlayer
                runOnMainSync { player.play() }
                try { await("VLC playback start") { player.isPlaying && player.time>500 } } catch(e:Exception) { error("Playback time=${player.time}, length=${player.length}, playing=${player.isPlaying}") }
                val before=player.time; Thread.sleep(1100); check(player.time>before) { "Video clock did not advance" }
                val viewport=field("viewport") as View
                val coords=IntArray(2); runOnMainSync { viewport.getLocationOnScreen(coords) }
                val cx=coords[0]+viewport.width/2f; val cy=coords[1]+viewport.height/2f
                pinch(cx,cy)
                val video=field("video") as View
                check(video.scaleX>1.4f) { "Pinch zoom was not applied: ${video.scaleX}" }
                swipe(cx,cy,cx+90,cy+60)
                check(kotlin.math.abs(video.translationX)>10f || kotlin.math.abs(video.translationY)>10f) { "Pan was not applied" }
                runOnMainSync { player.rate=1.5f }
                check(kotlin.math.abs(player.rate-1.5f)<.05f) { "Playback rate not applied" }
                runOnMainSync { player.time=7000L }
                await("Seek") { player.time>=6500L }
                Thread.sleep(1200)
                val saved=player.time
                runOnMainSync { activity!!.finish() }; Thread.sleep(700)
                activity=open(); player=field("player") as MediaPlayer
                await("Resume position") { player.isPlaying && player.time>=saved-1200 }
                result.putString("stream","PASS: HTTP HEAD/range/body/suffix/416/token checks; generated AVC playback; advancing clock; injected two-finger pinch; drag pan; 1.5x speed; seeking; persisted resume.\n")
                result.putInt("passed",13)
                runOnMainSync { activity!!.finish() }
                finish(Activity.RESULT_OK,result)
            } catch(e:Throwable) {
                result.putString("stream","FAIL: ${android.util.Log.getStackTraceString(e)}")
                activity?.let { runOnMainSync { it.finish() } }
                finish(Activity.RESULT_CANCELED,result)
            }
        }.start()
    }
    private fun await(label:String,condition:()->Boolean) { val deadline=SystemClock.uptimeMillis()+60000; while(SystemClock.uptimeMillis()<deadline) { if(condition()) return; Thread.sleep(200) }; error("Timeout: $label") }
    private fun bridgeChecks(file:File) {
        val node=object:Node {
            override val id=file.toURI().toString(); override val name=file.name; override val directory=false
            override val size get()=file.length(); override val modified get()=file.lastModified()
            override fun input()=file.inputStream(); override fun children():List<Node> = emptyList()
            override fun writable()=false; override fun available():Long?=null
            override fun output():java.io.OutputStream=error("read only")
            override fun create(name:String,directory:Boolean):Node=error("read only")
            override fun rename(name:String):Node=error("read only"); override fun delete()=false
        }
        SmbStreamServer(node).use { server ->
            fun request(method:String,range:String?=null,path:String=server.uri.toString()):Pair<Int,ByteArray> {
                val connection=java.net.URL(path).openConnection() as java.net.HttpURLConnection
                connection.requestMethod=method; connection.connectTimeout=10000; connection.readTimeout=10000
                range?.let { connection.setRequestProperty("Range",it) }
                return try { val code=connection.responseCode; code to (if(code in 200..299 && method!="HEAD") connection.inputStream.use { it.readBytes() } else byteArrayOf()) } finally { connection.disconnect() }
            }
            check(request("HEAD").first==200)
            val bytes=file.readBytes(); val range=request("GET","bytes=10-29")
            check(range.first==206); check(range.second.contentEquals(bytes.copyOfRange(10,30)))
            check(request("GET","bytes=-13").second.contentEquals(bytes.takeLast(13).toByteArray()))
            check(request("GET","bytes=${file.length()}-").first==416)
            check(request("HEAD",path=server.uri.toString()+"wrong").first==404)
        }
    }
    private fun swipe(x:Float,y:Float,ex:Float,ey:Float) {
        val down=SystemClock.uptimeMillis()
        fun event(action:Int,px:Float,py:Float) { val e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,px,py,0); e.source=android.view.InputDevice.SOURCE_TOUCHSCREEN; sendPointerSync(e); e.recycle() }
        event(MotionEvent.ACTION_DOWN,x,y); for(i in 1..10) { Thread.sleep(20); event(MotionEvent.ACTION_MOVE,x+(ex-x)*i/10,y+(ey-y)*i/10) }; event(MotionEvent.ACTION_UP,ex,ey); waitForIdleSync()
    }
    private fun pinch(cx:Float,cy:Float) {
        val down=SystemClock.uptimeMillis()
        fun event(action:Int,distance:Float,two:Boolean) {
            val count=if(two) 2 else 1
            val props=Array(count) { n->MotionEvent.PointerProperties().apply { id=n; toolType=MotionEvent.TOOL_TYPE_FINGER } }
            val coords=Array(count) { n->MotionEvent.PointerCoords().apply { x=cx+(if(n==0) -distance else distance); y=cy; pressure=1f; size=1f } }
            val e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,count,props,coords,0,0,1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,0)
            sendPointerSync(e); e.recycle()
        }
        event(MotionEvent.ACTION_DOWN,50f,false)
        event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),50f,true)
        for(i in 1..15) { Thread.sleep(25); event(MotionEvent.ACTION_MOVE,50f+i*6,true) }
        event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),140f,true)
        event(MotionEvent.ACTION_UP,140f,false); waitForIdleSync()
    }
    private fun encode(file:File) {
        val codec=MediaCodec.createEncoderByType("video/avc")
        val format=MediaFormat.createVideoFormat("video/avc",160,96).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE,120000); setInteger(MediaFormat.KEY_FRAME_RATE,30); setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1)
        }
        val muxer=MediaMuxer(file.path,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started=false
        try {
            codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start()
            val info=MediaCodec.BufferInfo(); var track=-1; var frame=0; var inputDone=false; var done=false
            while(!done) {
                if(!inputDone) {
                    val index=codec.dequeueInputBuffer(10000)
                    if(index>=0) {
                        val buffer=codec.getInputBuffer(index)!!; buffer.clear()
                        if(frame>=900) { codec.queueInputBuffer(index,0,0,frame*1_000_000L/30,MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone=true }
                        else { val data=ByteArray(160*96*3/2) { n -> if(n<160*96) (32+(frame%180)).toByte() else 128.toByte() }; buffer.put(data); codec.queueInputBuffer(index,0,data.size,frame*1_000_000L/30,0); frame++ }
                    }
                }
                val index=codec.dequeueOutputBuffer(info,10000)
                if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { track=muxer.addTrack(codec.outputFormat); muxer.start(); started=true }
                else if(index>=0) {
                    val buffer=codec.getOutputBuffer(index)!!
                    if(info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size=0
                    if(info.size>0) { check(started); buffer.position(info.offset); buffer.limit(info.offset+info.size); muxer.writeSampleData(track,buffer,info) }
                    done=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(index,false)
                }
            }
        } finally { try { codec.stop() } catch(_:Exception) {}; codec.release(); if(started) muxer.stop(); muxer.release() }
    }
}

