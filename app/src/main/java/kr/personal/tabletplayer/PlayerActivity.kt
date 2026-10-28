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
    private lateinit var overlay: LinearLayout
    private var controlsOpacity=50
    private var hideDelayMs=500L
    private var controlsDialog:AlertDialog?=null
    private var optionsOpen=false
    private val hideControls=Runnable { if(!dragging && !optionsOpen) overlay.visibility=View.GONE }
    private fun showControls() { overlay.visibility=View.VISIBLE; handler.removeCallbacks(hideControls); if(!dragging && !optionsOpen) handler.postDelayed(hideControls,hideDelayMs) }
    private lateinit var clock: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var scale = 1f
    private var px = 0f
    private var py = 0f
    private var dragging = false
    private var ready = false
    private var positionKey = ""
    private var bridge: SmbStreamServer? = null
    private var activeInput: PlaybackInput? = null
    private var inputGeneration=0
    private var softwareOnly=false
    private var inputMode="준비 중"
    private var lastPlaybackError=""
    private var errorDialog:AlertDialog?=null
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
        controlsOpacity=getPreferences(0).getInt("controlsOpacity",50).coerceIn(10,100)
        hideDelayMs=getPreferences(0).getLong("controlsHideMs",500).coerceIn(100,60000)
        overlay=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; alpha=controlsOpacity/100f; setBackgroundColor(0xcc000000.toInt()) }
        viewport.addView(overlay,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        clock = TextView(this).apply { setTextColor(0xffffffff.toInt()) }
        overlay.addView(clock)
        seek = SeekBar(this).apply { max = 1000 }
        overlay.addView(seek)
        val controls = LinearLayout(this)
        fun button(label: String, action: () -> Unit) { controls.addView(Button(this).apply { text=label; setOnClickListener { action(); showControls() } }, LinearLayout.LayoutParams(-2, -2)) }
        button("이전") { change(-1) }
        button("재생/멈춤") { if (player.isPlaying) player.pause() else player.play() }
        button("다음") { change(1) }
        button("배속") { val rates = floatArrayOf(.5f,.75f,1f,1.25f,1.5f,2f); AlertDialog.Builder(this).setItems(rates.map { "${it}×" }.toTypedArray()) { _, n -> player.rate = rates[n] }.show() }
        button("자막") { subtitles() }
        button("디코딩") { AlertDialog.Builder(this).setTitle("디코딩 방식").setItems(arrayOf("자동", "소프트웨어 호환 모드")) { _,n -> savePosition(); softwareOnly=n==1; open() }.show() }
        button("화면 초기화") { scale=1f; video.scaleX=1f; video.scaleY=1f; video.translationX=0f; video.translationY=0f }
        button("옵션") { controlOptions() }
        overlay.addView(HorizontalScrollView(this).apply { addView(controls) })
        overlay.setOnTouchListener { _,_ -> true }
        setContentView(root)
        softwareOnly=intent.getBooleanExtra("software",false)
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
                MediaPlayer.Event.EncounteredError -> showPlaybackError("E_VLC",if(positionKey.startsWith("smb:")) "NAS 영상 스트림을 재생하지 못했습니다. 연결·읽기 권한과 영상 형식을 확인하세요." else "파일 열기 단계 후 VLC 재생이 실패했습니다. 영상 형식·코덱을 확인하거나 소프트웨어 호환 모드를 시도하세요.")
            }
        } }
        var tapX=0f; var tapY=0f; var tapCandidate=false
        val slop=ViewConfiguration.get(this).scaledTouchSlop
        var previousSpan = 0f
        var focusX = 0f
        var focusY = 0f
        fun span(e: MotionEvent, a: Int=0, b: Int=1): Float = kotlin.math.hypot(e.getX(a)-e.getX(b), e.getY(a)-e.getY(b))
        viewport.setOnTouchListener { _, e ->
            when(e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { px=e.x; py=e.y; tapX=e.x; tapY=e.y; tapCandidate=true; previousSpan=0f }
                MotionEvent.ACTION_POINTER_DOWN -> if(e.pointerCount>=2) {
                    tapCandidate=false; previousSpan=span(e); focusX=(e.getX(0)+e.getX(1))/2; focusY=(e.getY(0)+e.getY(1))/2
                }
                MotionEvent.ACTION_MOVE -> {
                    if(kotlin.math.hypot(e.x-tapX,e.y-tapY)>slop) tapCandidate=false
                    if(e.pointerCount>=2) {
                        val nextSpan=span(e)
                        val nextX=(e.getX(0)+e.getX(1))/2; val nextY=(e.getY(0)+e.getY(1))/2
                        if(previousSpan>1f && nextSpan>1f) {
                            val nextScale=(scale*nextSpan/previousSpan).coerceIn(1f,5f)
                            val ratio=nextScale/scale
                            // Preserve the video point under the fingers while their centroid moves.
                            video.translationX=video.translationX*ratio+(nextX-viewport.width/2f)-(focusX-viewport.width/2f)*ratio
                            video.translationY=video.translationY*ratio+(nextY-viewport.height/2f)-(focusY-viewport.height/2f)*ratio
                            scale=nextScale; video.scaleX=scale; video.scaleY=scale; clampPan()
                        }
                        previousSpan=nextSpan; focusX=nextX; focusY=nextY
                    } else {
                        if(scale>1f) { video.translationX+=e.x-px; video.translationY+=e.y-py; clampPan() }
                        px=e.x; py=e.y
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    val remaining=(0 until e.pointerCount).filter { it!=e.actionIndex }
                    if(remaining.size>=2) {
                        val a=remaining[0]; val b=remaining[1]; previousSpan=span(e,a,b)
                        focusX=(e.getX(a)+e.getX(b))/2; focusY=(e.getY(a)+e.getY(b))/2
                    } else {
                        previousSpan=0f
                        remaining.firstOrNull()?.let { px=e.getX(it); py=e.getY(it) }
                    }
                }
                MotionEvent.ACTION_UP -> { previousSpan=0f; if(tapCandidate) { showControls(); viewport.performClick() }; tapCandidate=false }
                MotionEvent.ACTION_CANCEL -> { previousSpan=0f; tapCandidate=false }
            }; true
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, user: Boolean) {}
            override fun onStartTrackingTouch(s: SeekBar?) { dragging=true; handler.removeCallbacks(hideControls) }
            override fun onStopTrackingTouch(s: SeekBar?) { if(player.isSeekable) player.time=player.length*seek.progress/1000; dragging=false; showControls() }
        })
        open(); showControls()
    }
    private fun controlOptions() {
        optionsOpen=true; handler.removeCallbacks(hideControls)
        val form=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(24,16,24,16) }
        val opacityLabel=TextView(this)
        val opacity=SeekBar(this).apply { max=90; progress=controlsOpacity-10 }
        val delay=EditText(this).apply { inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL; setText("%.1f".format(java.util.Locale.US,hideDelayMs/1000.0)); hint="0.1~60.0초 (0.1초 단위)" }
        opacityLabel.text="버튼 불투명도: $controlsOpacity%"
        opacity.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s:SeekBar?,p:Int,user:Boolean) { opacityLabel.text="버튼 불투명도: ${p+10}%" }
            override fun onStartTrackingTouch(s:SeekBar?) {}
            override fun onStopTrackingTouch(s:SeekBar?) {}
        })
        form.addView(opacityLabel); form.addView(opacity)
        form.addView(TextView(this).apply { text="자동 숨김 시간 (초)" }); form.addView(delay)
        val dialog=AlertDialog.Builder(this).setTitle("재생 컨트롤 옵션").setView(form).setNegativeButton("취소",null).setPositiveButton("저장",null).create()
        controlsDialog=dialog
        dialog.setOnDismissListener { optionsOpen=false; showControls() }
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value=delay.text.toString().replace(',','.').toBigDecimalOrNull()
            if(value==null || value<java.math.BigDecimal("0.1") || value>java.math.BigDecimal("60.0") || value.stripTrailingZeros().scale()>1) { delay.error="0.1~60.0초를 0.1초 단위로 입력하세요"; return@setOnClickListener }
            controlsOpacity=opacity.progress+10; hideDelayMs=value.multiply(java.math.BigDecimal(1000)).toLong()
            overlay.alpha=controlsOpacity/100f
            getPreferences(0).edit().putInt("controlsOpacity",controlsOpacity).putLong("controlsHideMs",hideDelayMs).apply()
            dialog.dismiss()
        } }
        dialog.show()
    }
    private fun clampPan() { val x=viewport.width*(scale-1)/2; val y=viewport.height*(scale-1)/2; video.translationX=video.translationX.coerceIn(-x,x); video.translationY=video.translationY.coerceIn(-y,y) }
    private fun open() {
        val generation=++inputGeneration
        ready=false; handler.removeCallbacks(tick); errorDialog?.dismiss()
        player.stop(); activeInput?.close(); activeInput=null; bridge?.close(); bridge=null
        positionKey=queue[index]
        val key=positionKey
        inputMode="입력 준비 중"
        Thread {
            var prepared:PlaybackInput?=null
            var stream:SmbStreamServer?=null
            try {
                val uri=if(key.startsWith("smb:")) { SmbStreamServer(Storage.resolve(this,key)).also { stream=it }.uri } else Uri.parse(key)
                prepared=PlaybackInput.open(this,uri)
                val input=prepared!!
                runOnUiThread {
                    if(generation!=inputGeneration || isFinishing || isDestroyed) { input.close(); stream?.close() }
                    else {
                        activeInput=input; bridge=stream; inputMode=input.mode
                        try { val media=input.media(vlc); try { media.setHWDecoderEnabled(!softwareOnly,false); player.media=media } finally { media.release() }; player.play() }
                        catch(e:Exception) { activeInput?.close(); activeInput=null; bridge?.close(); bridge=null; showPlaybackError("E_MEDIA",e.message ?: "VLC 입력 초기화 실패") }
                    }
                }
            } catch(e:Exception) {
                prepared?.close(); stream?.close()
                runOnUiThread { if(generation==inputGeneration && !isFinishing && !isDestroyed) {
                    val reason=when(e) {
                        is SecurityException -> "영상 읽기 권한이 없습니다. 내부/SD/USB 폴더를 다시 연결하거나 파일을 다시 선택하세요."
                        is java.io.FileNotFoundException -> "선택한 파일을 열 수 없습니다. 이동·삭제 여부와 저장소 연결을 확인하세요."
                        else -> e.message ?: "입력을 열지 못했습니다"
                    }
                    showPlaybackError(if(e is SecurityException) "E_PERMISSION" else "E_OPEN",reason)
                } }
            }
        }.start()
    }
    private fun showPlaybackError(code:String,reason:String) {
        if(isFinishing || isDestroyed) return
        val source=Uri.parse(positionKey)
        lastPlaybackError="오류 $code\n$reason\n\n입력: ${source.scheme ?: "unknown"} / $inputMode\n문서 제공자: ${if(source.scheme=="content") source.authority else "해당 없음"}\n디코딩: ${if(softwareOnly) "소프트웨어" else "자동"}\n기기: ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n앱: 0.1.2 / LibVLC 3.7.7"
        File(filesDir,"playback-error.txt").writeText(lastPlaybackError)
        errorDialog?.dismiss()
        errorDialog=AlertDialog.Builder(this).setTitle("재생 실패").setMessage(lastPlaybackError)
            .setPositiveButton("닫기",null)
            .setNeutralButton("진단정보 복사") { _,_ -> getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("재생 진단",lastPlaybackError)); Toast.makeText(this,"진단정보를 복사했습니다",Toast.LENGTH_SHORT).show() }
            .setNegativeButton(if(softwareOnly) "자동 모드 재시도" else "소프트웨어 재시도") { _,_ -> softwareOnly=!softwareOnly; open() }.show()
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
    override fun onDestroy() { inputGeneration++; controlsDialog?.dismiss(); errorDialog?.dismiss(); handler.removeCallbacksAndMessages(null); if(::player.isInitialized) { player.stop(); player.detachViews(); player.release(); vlc.release() }; activeInput?.close(); bridge?.close(); super.onDestroy() }
    private fun time(ms: Long): String { val s=(ms/1000).coerceAtLeast(0); return "%d:%02d:%02d".format(s/3600,s/60%60,s%60) }
}



