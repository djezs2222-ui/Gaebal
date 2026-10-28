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
                var playbackUri=android.net.Uri.fromFile(clip)
                var forceSoftware=false
                fun open():Activity = startActivitySync(Intent(targetContext,PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("uri",playbackUri.toString()).putExtra("software",forceSoftware))
                activity=open(); waitForIdleSync(); await("Player window focus") { activity!!.hasWindowFocus() }
                sendStatus(1,Bundle().apply { putString("stream","Player window focused; waiting for VLC playback.\n") })
                fun field(name:String):Any=PlayerActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.get(activity)
                var player=field("player") as MediaPlayer
                runOnMainSync { player.play() }
                try { await("VLC playback start") { player.isPlaying && player.time>500 } } catch(e:Exception) { error("Playback time=${player.time}, length=${player.length}, playing=${player.isPlaying}") }
                val before=player.time
                val videoView=field("video") as View
                fun texture(view:View):TextureView? {
                    if(view is TextureView) return view
                    if(view is ViewGroup) for(n in 0 until view.childCount) texture(view.getChildAt(n))?.let { return it }
                    return null
                }
                var decoded:TextureView?=null
                await("Decoded TextureView") { runOnMainSync { decoded=texture(videoView) }; decoded?.isAvailable==true }
                fun brightness():Double {
                    var average=0.0
                    runOnMainSync {
                        val bitmap=decoded!!.getBitmap(32,32) ?: error("No decoded bitmap")
                        val pixels=IntArray(1024); bitmap.getPixels(pixels,0,32,0,0,32,32)
                        average=pixels.map { android.graphics.Color.red(it) }.average(); bitmap.recycle()
                    }
                    return average
                }
                val firstFrame=brightness(); check(firstFrame>5.0) { "Decoded output is black" }
                Thread.sleep(1100); check(player.time>before) { "Video clock did not advance" }
                check(kotlin.math.abs(brightness()-firstFrame)>3.0) { "Video pixels did not advance" }
                runOnMainSync { player.pause() }
                val viewport=field("viewport") as View
                val coords=IntArray(2); runOnMainSync { viewport.getLocationOnScreen(coords) }
                val cx=coords[0]+viewport.width/2f; val cy=coords[1]+viewport.height/2f
                val overlay=field("overlay") as View
                check(overlay.alpha==.5f) { "Default opacity" }
                Thread.sleep(3300); check(overlay.visibility==View.GONE) { "Default 3-second controls did not auto-hide" }
                tap(cx,cy)
                Thread.sleep(400)
                check(overlay.visibility==View.VISIBLE) { "Single tap did not reveal controls" }
                Thread.sleep(3300); check(overlay.visibility==View.GONE) { "Controls did not hide after tap" }
                runOnMainSync { PlayerActivity::class.java.getDeclaredMethod("controlOptions").apply { isAccessible=true }.invoke(activity) }
                val options=field("controlsDialog") as AlertDialog
                fun descendants(v:View):List<View> = listOf(v)+(if(v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList())
                runOnMainSync {
                    val views=descendants(options.window!!.decorView)
                    views.filterIsInstance<android.widget.SeekBar>().single().progress=60
                    views.filterIsInstance<android.widget.EditText>().first().setText("0.15")
                    options.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    check(options.isShowing) { "Invalid 0.1-second step accepted" }
                    views.filterIsInstance<android.widget.EditText>().first().setText("1.2")
                    options.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                }
                check(kotlin.math.abs(overlay.alpha-.7f)<.001f) { "Opacity option not applied" }
                Thread.sleep(700); check(overlay.visibility==View.VISIBLE) { "Custom timeout ignored" }
                Thread.sleep(700); check(overlay.visibility==View.GONE) { "Custom timeout did not expire" }
                interactionChecks(activity as PlayerActivity,player,viewport)
                pinch(cx,cy)
                val video=field("video") as View
                check(video.scaleX>1.4f) { "Pinch zoom was not applied: ${video.scaleX}" }
                swipe(cx,cy,cx+90,cy+60)
                check(kotlin.math.abs(video.translationX)>10f || kotlin.math.abs(video.translationY)>10f) { "Pan was not applied" }
                uiAutomation.takeScreenshot()?.let { image -> File(targetContext.filesDir,"smoke-zoom.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; image.recycle() }
                runOnMainSync { player.rate=1.5f }
                check(kotlin.math.abs(player.rate-1.5f)<.05f) { "Playback rate not applied" }
                runOnMainSync { player.time=7000L }
                await("Seek") { player.time>=6500L }
                Thread.sleep(1200)
                val saved=player.time
                runOnMainSync { activity!!.finish() }; Thread.sleep(700)
                activity=open(); player=field("player") as MediaPlayer
                check(kotlin.math.abs((field("overlay") as View).alpha-.7f)<.001f && field("hideDelayMs")==1200L) { "Control preferences not retained" }
                await("Resume position") { player.isPlaying && player.time>=saved-1200 }
                check(field("jumpSeconds")==4 && field("altSeconds")==6 && field("ctrlSeconds")==12 && field("shiftSeconds")==31 && field("volumeStep")==4 && field("holdMultiplier")==3f) { "New options did not persist after closing" }
                check((field("segments") as List<*>).all { (it as PlaybackRules.Segment).valid() }) { "Segment endpoints did not persist" }
                runOnMainSync { activity!!.finish() }; Thread.sleep(700)
                for((path,software) in listOf("영상 한글.mp4" to false,"offset.mp4" to true)) {
                    playbackUri=android.net.Uri.Builder().scheme("content").authority(targetContext.packageName+".testmedia").appendPath(path).build()
                    forceSoftware=software
                    activity=open(); waitForIdleSync(); player=field("player") as MediaPlayer
                    await("Document playback $path") { player.isPlaying && player.time>500 }
                    await("Document video texture $path") { runOnMainSync { decoded=texture(field("video") as View) }; decoded?.isAvailable==true }
                    await("Document rendered pixels $path") { brightness()>5.0 }
                    val start=player.time; val light=brightness(); Thread.sleep(1100)
                    check(player.time>start && kotlin.math.abs(brightness()-light)>3.0) { "Document frame did not advance: $path" }
                    runOnMainSync { player.time=7000L }
                    await("Document seek $path") { player.time in 6500L..10000L }
                    check((field("inputMode") as String).contains("파일 핸들")) { "Document did not use Android descriptor" }
                    runOnMainSync { activity!!.finish() }; Thread.sleep(700)
                    activity=open(); player=field("player") as MediaPlayer
                    await("Document resume $path") { player.isPlaying && player.time>=6000L }
                    runOnMainSync { activity!!.finish() }; Thread.sleep(700)
                    sendStatus(1,Bundle().apply { putString("stream","PASS document URI: $path; descriptor; pixels; seek; resume; software=$software\n") })
                }
                playbackUri=android.net.Uri.parse("content://${targetContext.packageName}.testmedia/denied")
                activity=open(); waitForIdleSync()
                await("Readable permission error") { (field("lastPlaybackError") as String).contains("E_PERMISSION") }
                runOnMainSync { activity!!.finish() }; Thread.sleep(500)
                queueChecks(clip)
                result.putString("stream","PASS: file playback/pixels/pinch/pan/speed/seek/resume; HTTP range bridge; content URI with Korean filename; offset/length document; software mode; permission error diagnostics; control opacity/single tap/auto-hide/options; temporary queue/navigation/sort/centering/auto-next/cleanup; named playlist create/append/dedupe/recent5.\n")
                result.putInt("passed",28)
                runOnMainSync { activity!!.finish() }
                finish(Activity.RESULT_OK,result)
            } catch(e:Throwable) {
                result.putString("stream","FAIL: ${android.util.Log.getStackTraceString(e)}")
                activity?.let { runOnMainSync { it.finish() } }
                finish(Activity.RESULT_CANCELED,result)
            }
        }.start()
    }
    private fun interactionChecks(activity:PlayerActivity,player:MediaPlayer,viewport:View) {
        fun field(name:String):Any=PlayerActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.get(activity)!!
        fun method(name:String)=PlayerActivity::class.java.getDeclaredMethod(name).apply { isAccessible=true }
        fun key(code:Int,meta:Int=0) { runOnMainSync {
            activity.dispatchKeyEvent(KeyEvent(0,SystemClock.uptimeMillis(),KeyEvent.ACTION_DOWN,code,0,meta))
            activity.dispatchKeyEvent(KeyEvent(0,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,code,0,meta))
        }; Thread.sleep(250) }
        runOnMainSync { player.pause(); player.time=12000 }; Thread.sleep(300)
        key(KeyEvent.KEYCODE_DPAD_LEFT); check(player.time in 8500L..9500L) { "Default keyboard 3 seconds: ${player.time}" }
        key(KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.META_ALT_RIGHT_ON or KeyEvent.META_ALT_ON)
        check(player.time in 13500L..14500L) { "Right Alt 5 seconds: ${player.time}" }
        key(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.META_CTRL_LEFT_ON or KeyEvent.META_CTRL_ON)
        check(player.time in 3500L..4500L) { "Left Ctrl 10 seconds: ${player.time}" }
        key(KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.META_SHIFT_RIGHT_ON or KeyEvent.META_SHIFT_ON)
        check(player.time>=player.length-500) { "Right Shift 30 seconds clamp: ${player.time}" }
        // All four bracket pairs, including both physical sides of each modifier.
        val metas=listOf(0,KeyEvent.META_ALT_LEFT_ON or KeyEvent.META_ALT_ON,KeyEvent.META_CTRL_RIGHT_ON or KeyEvent.META_CTRL_ON,KeyEvent.META_SHIFT_LEFT_ON or KeyEvent.META_SHIFT_ON)
        metas.forEachIndexed { n,meta ->
            runOnMainSync { player.time=(1000+n*4000).toLong() }; Thread.sleep(200); key(KeyEvent.KEYCODE_LEFT_BRACKET,meta)
            runOnMainSync { player.time=(3000+n*4000).toLong() }; Thread.sleep(200); key(KeyEvent.KEYCODE_RIGHT_BRACKET,meta)
        }
        val segments=field("segments") as List<*>
        check(segments.all { (it as PlaybackRules.Segment).valid() }) { "Keyboard segment endpoints" }
        val seek=field("seek") as SegmentSeekBar
        check(seek.segments.size==4) { "Four colored segment markers" }
        runOnMainSync {
            PlayerActivity::class.java.getDeclaredField("segmentEnabled").apply { isAccessible=true }.setBoolean(activity,true)
            player.time=3000; method("checkSegments").invoke(activity)
        }
        Thread.sleep(250); check(player.time in 500L..1500L) { "Segment loop starts at first: ${player.time}" }
        runOnMainSync { player.time=3100; method("checkSegments").invoke(activity) }
        Thread.sleep(250); check(player.time in 4500L..5500L) { "Segment loop advances: ${player.time}" }
        runOnMainSync { PlayerActivity::class.java.getDeclaredField("segmentEnabled").apply { isAccessible=true }.setBoolean(activity,false) }
        val coords=IntArray(2); runOnMainSync { viewport.getLocationOnScreen(coords) }
        val x=coords[0]+viewport.width/2f; val y=coords[1]+viewport.height*.3f
        tap(x,y); Thread.sleep(80); tap(x,y); Thread.sleep(400)
        check(player.isPlaying) { "Center double tap did not resume" }
        tap(x,y); Thread.sleep(80); tap(x,y); Thread.sleep(400)
        check(!player.isPlaying) { "Center double tap did not pause" }
        val lx=coords[0]+viewport.width*.15f
        swipe(lx,y,lx,y-100); Thread.sleep(250)
        check(activity.window.attributes.screenBrightness>=.01f) { "Brightness swipe" }
        val audio=targetContext.getSystemService(AudioManager::class.java)
        val max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        runOnMainSync { audio.setStreamVolume(AudioManager.STREAM_MUSIC,max/2,0) }
        val volume=audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        key(KeyEvent.KEYCODE_DPAD_UP); check(audio.getStreamVolume(AudioManager.STREAM_MUSIC)>volume) { "Keyboard volume up" }
        key(KeyEvent.KEYCODE_DPAD_DOWN); check(audio.getStreamVolume(AudioManager.STREAM_MUSIC)<=volume) { "Keyboard volume down" }
        val rx=coords[0]+viewport.width*.85f
        runOnMainSync { player.time=10000 }; Thread.sleep(200)
        tap(lx,y); Thread.sleep(80); tap(lx,y); Thread.sleep(350)
        check(player.time in 6500L..7500L) { "Left double tap 3 seconds: ${player.time}" }
        tap(rx,y); Thread.sleep(80); tap(rx,y); Thread.sleep(350)
        check(player.time in 9500L..10500L) { "Right double tap 3 seconds: ${player.time}" }
        runOnMainSync { player.time=12000; player.play() }; Thread.sleep(250)
        val begin=PlayerActivity::class.java.getDeclaredMethod("beginHold",Int::class.javaPrimitiveType).apply { isAccessible=true }
        runOnMainSync { begin.invoke(activity,0) }; Thread.sleep(900)
        check(player.time<11500) { "Reverse hold did not seek backwards" }
        runOnMainSync { method("endHold").invoke(activity) }; Thread.sleep(250)
        check(player.isPlaying) { "Release did not restore playing state" }
        runOnMainSync { player.pause() }; await("Pause before forward hold") { !player.isPlaying }
        runOnMainSync { begin.invoke(activity,2) }; Thread.sleep(700)
        runOnMainSync { method("endHold").invoke(activity) }; Thread.sleep(200)
        check(!player.isPlaying) { "Release did not preserve paused state" }
        runOnMainSync {
            method("controlOptions").invoke(activity)
            val dialog=field("controlsDialog") as AlertDialog
            fun descendants(v:View):List<View> = listOf(v)+(if(v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList())
            val fields=descendants(dialog.window!!.decorView).filterIsInstance<android.widget.EditText>()
            listOf("4","6","12","31","4","3").forEachIndexed { n,value -> fields[n+1].setText(value) }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            check(!dialog.isShowing) { "Valid playback options rejected" }
            player.play()
        }
        sendStatus(1,Bundle().apply { putString("stream","PASS interactions: keyboard seek/modifier sides, four bracket segments/markers/cycling, center double tap, brightness swipe, reverse/forward hold state restoration.\n") })
    }
    private fun queueChecks(clip:File) {
        val entries=(0 until 21).map { n -> val f=File(targetContext.filesDir,"queue-video-$n.mp4"); clip.copyTo(f,true); QueueEntry(android.net.Uri.fromFile(f).toString(),"Video %02d.mp4".format(20-n),n.toLong()*100,21L-n) }
        check(Playlists.decode(Playlists.encode(entries))==entries)
        val session=File(targetContext.cacheDir,"queue-${java.util.UUID.randomUUID()}.json").apply { writeText(Playlists.encode(entries)) }
        val a=startActivitySync(Intent(targetContext,PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("queueFile",session.name).putExtra("temporary",true).putExtra("index",10)) as PlayerActivity
        fun field(name:String):Any=PlayerActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.get(a)!!
        fun method(name:String)=PlayerActivity::class.java.getDeclaredMethod(name).apply { isAccessible=true }
        fun descendants(v:View):List<View> = listOf(v)+(if(v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList())
        val player=field("player") as MediaPlayer
        await("Queue initial video") { player.isPlaying && player.time>500 }
        runOnMainSync { method("currentQueue").invoke(a) }; waitForIdleSync()
        var dialog=field("queueDialog") as AlertDialog
        var list:android.widget.ListView?=null
        runOnMainSync { list=descendants(dialog.window!!.decorView).filterIsInstance<android.widget.ListView>().single() }
        Thread.sleep(300)
        check(list!!.adapter.count==21 && list!!.checkedItemPosition==10)
        check(list!!.firstVisiblePosition<10 && list!!.lastVisiblePosition>10) { "Playing row was not centered" }
        fun sortBySize() {
            runOnMainSync { descendants(dialog.window!!.decorView).filterIsInstance<android.widget.Button>().first { it.text.startsWith("정렬:") }.performClick() }
            waitForIdleSync()
            val node=uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText("크기순").firstOrNull() ?: error("Sort popup missing")
            check(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)); waitForIdleSync()
            dialog=field("queueDialog") as AlertDialog
            runOnMainSync { list=descendants(dialog.window!!.decorView).filterIsInstance<android.widget.ListView>().single() }
        }
        sortBySize(); check((field("queue") as List<*>).first()==entries.first().id)
        sortBySize(); check((field("queue") as List<*>).first()==entries.last().id)
        check((field("queue") as List<*>)[field("index") as Int]==entries[10].id) { "Sorting changed playing identity" }
        runOnMainSync { list!!.performItemClick(null,2,2) }
        await("Queue row immediate playback") { field("index")==2 && player.isPlaying && player.time>300 }
        check((field("queue") as List<*>).size==21 && field("temporary")==true) { "Selecting row rebuilt temporary queue" }
        // Actual named documents, using an unexported SAF fixture provider.
        val files=PlaylistFiles(a) { _,_,_ -> }
        val folder=android.provider.DocumentsContract.buildTreeDocumentUri(targetContext.packageName+".testlists","lists")
        val prefs=targetContext.getSharedPreferences("playlistFiles",0); prefs.edit().clear().putString("folder",folder.toString()).commit()
        val create=PlaylistFiles::class.java.getDeclaredMethod("create",String::class.java,List::class.java).apply { isAccessible=true }
        for(n in 0..5) {
            runOnMainSync { create.invoke(files,"테스트$n",listOf(entries[0])) }
            await("Named list save $n") { check(files.lastError.isEmpty()) { files.lastError }; try { val recent=org.json.JSONArray(prefs.getString("recent","[]")); recent.length()>0 && recent.getJSONObject(0).getString("name")=="테스트$n.tplaylist.json" } catch(_:Exception) { false } }
        }
        val recent=org.json.JSONArray(prefs.getString("recent","[]")); check(recent.length()==5); check(recent.getJSONObject(4).getString("name")=="테스트1.tplaylist.json")
        val id=recent.getJSONObject(0).getString("id")
        val add=PlaylistFiles::class.java.getDeclaredMethod("add",String::class.java,List::class.java).apply { isAccessible=true }
        runOnMainSync { add.invoke(files,id,listOf(entries[0],entries[1])) }
        await("Named append and dedupe") { check(files.lastError.isEmpty()) { files.lastError }; try { val stored=Storage.resolve(targetContext,id).input().use { it.readBytes().toString(Charsets.UTF_8) }; Playlists.decode(stored)==entries.take(2) } catch(_:Exception) { false } }
        // Provider write is verified before the progress dialog is dismissed.
        Thread.sleep(500)
        runOnMainSync { player.play(); player.time=player.length-700 }
        await("Sequential next video") { field("index")==3 && player.isPlaying }
        runOnMainSync { method("currentQueue").invoke(a) }; waitForIdleSync(); dialog=field("queueDialog") as AlertDialog
        runOnMainSync { list=descendants(dialog.window!!.decorView).filterIsInstance<android.widget.ListView>().single(); list!!.performItemClick(null,20,20) }
        await("Last video playing") { field("index")==20 && player.isPlaying && player.time>300 }
        runOnMainSync { player.time=player.length-700 }
        await("Temporary queue removed at end") { a.isFinishing && (field("queue") as List<*>).isEmpty() }
        await("Temporary queue file removed") { !session.exists() }
        sendStatus(1,Bundle().apply { putString("stream","PASS playlist: 21-row centering, ascending/descending sort keeps video identity, direct jump without rebuild, sequential next, final temporary cleanup; named SAF documents create/append/dedupe/recent5.\n") })
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
    private fun tap(x:Float,y:Float) {
        val down=SystemClock.uptimeMillis()
        fun event(action:Int) { val e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0); e.source=InputDevice.SOURCE_TOUCHSCREEN; sendPointerSync(e); e.recycle() }
        event(MotionEvent.ACTION_DOWN); Thread.sleep(50); event(MotionEvent.ACTION_UP); waitForIdleSync()
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

