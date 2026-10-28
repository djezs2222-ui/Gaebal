package kr.personal.tabletplayer

import android.app.*
import android.content.*
import android.net.Uri
import android.os.*
import android.view.*
import android.widget.*
import org.videolan.libvlc.*
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File

class PlayerActivity : Activity() {
    private lateinit var vlc: LibVLC
    private lateinit var player: MediaPlayer
    private lateinit var video: VLCVideoLayout
    private lateinit var viewport: FrameLayout
    private lateinit var seek: SeekBar
    private lateinit var clock: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var scale = 1f
    private var px = 0f
    private var py = 0f
    private var dragging = false
    private var ready = false
    private var positionKey = ""
    private var bridge: SmbStreamServer? = null
    private var queue = arrayListOf<String>()
    private var index = 0
    private val tick = object : Runnable {
        override fun run() {
            if (!ready) return
            if (!dragging && player.length > 0) seek.progress = (player.time * 1000 / player.length).toInt()
            clock.text = "${time(player.time)} / ${time(player.length)}"
            savePosition()
            handler.postDelayed(this, 1000)
        }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setOnApplyWindowInsetsListener { view, insets -> @Suppress("DEPRECATION") view.setPadding(0,insets.systemWindowInsetTop,0,insets.systemWindowInsetBottom); insets } }
        viewport = FrameLayout(this).apply { clipChildren = true; setBackgroundColor(0xff000000.toInt()) }
        video = VLCVideoLayout(this)
        viewport.addView(video, FrameLayout.LayoutParams(-1, -1))
        root.addView(viewport, LinearLayout.LayoutParams(-1, 0, 1f))
        clock = TextView(this)
        root.addView(clock)
        seek = SeekBar(this).apply { max = 1000 }
        root.addView(seek)
        val controls = LinearLayout(this)
        fun button(label: String, action: () -> Unit) { controls.addView(Button(this).apply { text=label; setOnClickListener { action() } }, LinearLayout.LayoutParams(-2, -2)) }
        button("이전") { change(-1) }
        button("재생/멈춤") { if (player.isPlaying) player.pause() else player.play() }
        button("다음") { change(1) }
        button("배속") { val rates = floatArrayOf(.5f,.75f,1f,1.25f,1.5f,2f); AlertDialog.Builder(this).setItems(rates.map { "${it}×" }.toTypedArray()) { _, n -> player.rate = rates[n] }.show() }
        button("자막") { subtitles() }
        button("화면 초기화") { scale=1f; video.scaleX=1f; video.scaleY=1f; video.translationX=0f; video.translationY=0f }
        root.addView(HorizontalScrollView(this).apply { addView(controls) })
        setContentView(root)
        vlc = LibVLC(this, arrayListOf("--network-caching=1500"))
        player = MediaPlayer(vlc)
        // TextureView is required: transforming a SurfaceView does not reliably transform video.
        player.attachViews(video, null, false, true)
        queue = intent.getStringArrayListExtra("queue") ?: arrayListOf(intent.getStringExtra("uri") ?: "")
        index = intent.getIntExtra("index", 0).coerceIn(0, (queue.size-1).coerceAtLeast(0))
        player.setEventListener { event -> runOnUiThread {
            when(event.type) {
                MediaPlayer.Event.Playing -> { if (!ready) { ready=true; player.time=getPreferences(0).getLong(positionKey,0); handler.post(tick) } }
                MediaPlayer.Event.EndReached -> { getPreferences(0).edit().remove(positionKey).apply(); ready=false; handler.removeCallbacks(tick); if(index < queue.lastIndex) change(1) }
                MediaPlayer.Event.EncounteredError -> Toast.makeText(this,"재생 실패: 경로 또는 NAS 연결을 확인하세요",Toast.LENGTH_LONG).show()
            }
        } }
        val detector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                scale = (scale * d.scaleFactor).coerceIn(1f, 5f)
                video.scaleX=scale; video.scaleY=scale; clampPan(); return true
            }
        })
        viewport.setOnTouchListener { _, e ->
            detector.onTouchEvent(e)
            when(e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { px=e.x; py=e.y }
                MotionEvent.ACTION_MOVE -> {
                    if(e.pointerCount==1 && !detector.isInProgress && scale>1f) { video.translationX += e.x-px; video.translationY += e.y-py; clampPan() }
                    px=e.x; py=e.y
                }
                MotionEvent.ACTION_POINTER_UP -> { val n=if(e.actionIndex==0) 1 else 0; if(n<e.pointerCount) { px=e.getX(n); py=e.getY(n) } }
            }; true
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, user: Boolean) {}
            override fun onStartTrackingTouch(s: SeekBar?) { dragging=true }
            override fun onStopTrackingTouch(s: SeekBar?) { if(player.isSeekable) player.time=player.length*seek.progress/1000; dragging=false }
        })
        open()
    }
    private fun clampPan() { val x=viewport.width*(scale-1)/2; val y=viewport.height*(scale-1)/2; video.translationX=video.translationX.coerceIn(-x,x); video.translationY=video.translationY.coerceIn(-y,y) }
    private fun open() {
        ready=false; handler.removeCallbacks(tick); bridge?.close(); bridge=null
        positionKey=queue[index]
        Thread {
            try {
                val uri=if(positionKey.startsWith("smb:")) { SmbStreamServer(Storage.resolve(this,positionKey)).also { bridge=it }.uri } else Uri.parse(positionKey)
                runOnUiThread { if(!isFinishing) { val media=Media(vlc,uri); player.media=media; media.release(); player.play() } }
            } catch(e: Exception) { runOnUiThread { Toast.makeText(this,e.message,Toast.LENGTH_LONG).show() } }
        }.start()
    }
    private fun change(delta: Int) { val next=index+delta; if(next !in queue.indices) return; savePosition(); player.stop(); index=next; open() }
    private fun savePosition() { if(ready && player.time>0) getPreferences(0).edit().putLong(positionKey,player.time).apply() }
    private fun subtitles() {
        val tracks=player.spuTracks.orEmpty()
        AlertDialog.Builder(this).setTitle("자막").setItems((listOf("외부 자막 파일 열기", "자막 끄기")+tracks.map { it.name }).toTypedArray()) { _, n ->
            when(n) { 0 -> startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type="*/*"; addCategory(Intent.CATEGORY_OPENABLE) },10); 1 -> player.setSpuTrack(-1); else -> player.setSpuTrack(tracks[n-2].id) }
        }.show()
    }
    @Deprecated("Activity result") override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request,result,data)
        if(request==10 && result==RESULT_OK) data?.data?.let { uri ->
            // VLC can read private file paths; copy content-provider subtitles into a private cache file.
            Thread { try { val file=File(cacheDir,"external-subtitle.srt"); contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }; runOnUiThread { player.addSlave(org.videolan.libvlc.interfaces.IMedia.Slave.Type.Subtitle,Uri.fromFile(file),true) } } catch(e: Exception) { runOnUiThread { Toast.makeText(this,e.message,Toast.LENGTH_LONG).show() } } }.start()
        }
    }
    override fun onPause() { savePosition(); if(::player.isInitialized) player.pause(); super.onPause() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); if(::player.isInitialized) { player.stop(); player.detachViews(); player.release(); vlc.release() }; bridge?.close(); super.onDestroy() }
    private fun time(ms: Long): String { val s=(ms/1000).coerceAtLeast(0); return "%d:%02d:%02d".format(s/3600,s/60%60,s%60) }
}



