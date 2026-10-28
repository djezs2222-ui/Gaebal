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
    private var hideDelayMs=3000L
    private var controlsDialog:AlertDialog?=null
    private var controlTouchActive=false
    private var optionsOpen=false
    private val hideControls=Runnable { if(!dragging && !optionsOpen && !controlTouchActive) overlay.visibility=View.GONE }
    private fun showControls() { overlay.visibility=View.VISIBLE; handler.removeCallbacks(hideControls); if(!dragging && !optionsOpen && !controlTouchActive) handler.postDelayed(hideControls,hideDelayMs) }
    private lateinit var clock: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var scale = 1f
    private var px = 0f
    private var py = 0f
    private var dragging = false
    private var ready = false
    private var lastSaveAt=0L
    private var positionKey = ""
    private var bridge: SmbStreamServer? = null
    private var activeInput: PlaybackInput? = null
    private var inputGeneration=0
    private var softwareOnly=false
    private var inputMode="준비 중"
    private var lastPlaybackError=""
    private var errorDialog:AlertDialog?=null
    private var queue = arrayListOf<String>()
    private var queueItems=listOf<QueueEntry>()
    private var temporary=true
    private var resumeAfterPicker=false
    private var stateFileName="queue-${java.util.UUID.randomUUID()}.json"
    private var queueTitle="임시 재생목록"
    private var queueSort=-1
    private var queueDescending=false
    private var queueDialog:AlertDialog?=null
    private val playlistFiles by lazy { PlaylistFiles(this) { items,at,title ->
        savePosition(); queueItems=items; queue=ArrayList(items.map { it.id }); index=at; temporary=false; queueTitle=title; history.clear(); rebuildShuffle(); open()
    } }
    private var index = 0
    private val settings by lazy { getPreferences(0) }
    private var jumpSeconds=3
    private var altSeconds=5
    private var ctrlSeconds=10
    private var shiftSeconds=30
    private var volumeStep=3
    private var holdMultiplier=2f
    private var shuffle=false
    private var repeatMode=0 // 0 off, 1 current video, 2 whole queue
    private var shuffleRemaining=mutableListOf<Int>()
    private val history=mutableListOf<Int>()
    private var segments=MutableList(4) { PlaybackRules.Segment() }
    private var pendingSegmentStart:Long?=null
    private var segmentEnabled=false
    private var activeSegment=-1
    private var holdDirection=0
    private var holdWasPlaying=false
    private var holdRate=1f
    private var touchZone=1
    private var touchConsumed=false
    private var verticalGesture=false
    private var initialVolume=0
    private var initialBrightness=.5f
    private val audio by lazy { getSystemService(android.media.AudioManager::class.java) }
    private val holdTick=object:Runnable {
        override fun run() {
            if(holdDirection==0 || !ready) return
            seekTo(player.time+(holdDirection*holdRate*holdMultiplier*120).toLong())
            handler.postDelayed(this,120)
        }
    }
    private fun loadSettings() {
        jumpSeconds=settings.getInt("jumpSeconds",3); altSeconds=settings.getInt("altSeconds",5)
        ctrlSeconds=settings.getInt("ctrlSeconds",10); shiftSeconds=settings.getInt("shiftSeconds",30)
        volumeStep=settings.getInt("volumeStep",3); holdMultiplier=settings.getFloat("holdMultiplier",2f)
        shuffle=settings.getBoolean("shuffle",false); repeatMode=settings.getInt("repeatMode",0)
        // Migrate the original 0.5-second default once; later user choices are retained.
        if(!settings.getBoolean("hideDefault3",false)) {
            if(settings.getLong("controlsHideMs",3000)==500L) settings.edit().putLong("controlsHideMs",3000).apply()
            settings.edit().putBoolean("hideDefault3",true).apply()
        }
    }
    private fun seekTo(target:Long) {
        if(ready && player.isSeekable) player.time=target.coerceIn(0,player.length.coerceAtLeast(0))
    }
    private fun jump(seconds:Int) { if(ready) { seekTo(player.time+seconds*1000L); showControls() } }
    private fun togglePlaying() {
        if(!ready && queue.isNotEmpty()) { open(); showControls(); return }
        if(ready) { if(player.isPlaying) player.pause() else player.play(); showControls() }
    }
    private fun volumePercent(percent:Int) {
        val max=audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        val current=audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        val step=kotlin.math.round(max*percent/100.0).toInt().let { if(percent<0) it.coerceAtMost(-1) else it.coerceAtLeast(1) }
        setVolume(current+step)
    }
    private fun setVolume(value:Int) {
        val max=audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC,value.coerceIn(0,max),0)
        Toast.makeText(this,"볼륨 ${(audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)*100f/max).toInt()}%",Toast.LENGTH_SHORT).show()
    }
    private fun beginHold(zone:Int) {
        if(!ready || zone==1) return
        touchConsumed=true; holdDirection=if(zone==0) -1 else 1
        holdWasPlaying=player.isPlaying; holdRate=player.rate
        player.pause(); handler.post(holdTick)
    }
    private fun endHold() {
        if(holdDirection==0) return
        holdDirection=0; handler.removeCallbacks(holdTick)
        if(ready) { player.rate=holdRate; if(holdWasPlaying) player.play() }
    }
    private fun rebuildShuffle() {
        shuffleRemaining=(queue.indices.filter { it!=index }).shuffled().toMutableList()
    }
    private fun loadSegments() {
        segments=MutableList(4) { n -> PlaybackRules.Segment(settings.getLong("ab:$positionKey:$n:start",-1),settings.getLong("ab:$positionKey:$n:end",-1)) }
        segmentEnabled=settings.getBoolean("ab:$positionKey:enabled",false); activeSegment=-1
        updateMarkers()
    }
    private fun updateMarkers() { (seek as? SegmentSeekBar)?.let { it.segments=segments.toList(); it.duration=player.length; it.active=if(segmentEnabled) activeSegment else -1; it.invalidate() } }
    private fun checkSegments() {
        if(!segmentEnabled || holdDirection!=0 || dragging || !ready) return
        PlaybackRules.segmentTarget(segments,activeSegment,player.time)?.let { (slot,target) -> activeSegment=slot; seekTo(target) }
        updateMarkers()
    }
    private fun setSegment(slot:Int,end:Boolean) {
        if(!ready) return
        val old=segments[slot]; val at=player.time.coerceAtLeast(0)
        if(end && (old.start<0 || at<=old.start)) { Toast.makeText(this,"시작점보다 뒤에서 종료점을 지정하세요",Toast.LENGTH_SHORT).show(); return }
        segments[slot]=if(end) old.copy(end=at) else PlaybackRules.Segment(at,old.end.takeIf { it>at } ?: -1)
        val part=segments[slot]
        settings.edit().putLong("ab:$positionKey:$slot:start",part.start).putLong("ab:$positionKey:$slot:end",part.end).apply()
        activeSegment=-1; updateMarkers()
        Toast.makeText(this,"${slot+1}번 ${if(end) "종료" else "시작"}: ${time(at)}",Toast.LENGTH_SHORT).show()
    }
    private fun segmentOptions() {
        optionsOpen=true; handler.removeCallbacks(hideControls)
        val form=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        val enabled=CheckBox(this).apply { text="구간 반복 켜기 · 1→2→3→4 순환"; isChecked=segmentEnabled }; form.addView(enabled)
        form.addView(TextView(this).apply { text="완성된 구간만 순환합니다. 구간 반복이 영상·목록 반복보다 우선합니다." })
        val dialog=AlertDialog.Builder(this).setTitle("구간 반복 · 빨강/파랑/노랑/초록").setView(form).setPositiveButton("닫기",null).create()
        enabled.setOnCheckedChangeListener { _,value -> segmentEnabled=value; activeSegment=-1; settings.edit().putBoolean("ab:$positionKey:enabled",value).apply(); updateMarkers() }
        segments.forEachIndexed { n,part ->
            val row=LinearLayout(this)
            row.addView(TextView(this).apply { text="${n+1}: ${if(part.start>=0) time(part.start) else "—"} ~ ${if(part.end>=0) time(part.end) else "—"}"; setTextColor(SegmentSeekBar.COLORS[n]) },LinearLayout.LayoutParams(0,-2,1f))
            fun action(label:String,job:()->Unit) { row.addView(Button(this).apply { text=label; setOnClickListener { job(); dialog.dismiss(); segmentOptions() } }) }
            action("시작") { setSegment(n,false) }; action("끝") { setSegment(n,true) }
            action("삭제") { segments[n]=PlaybackRules.Segment(); settings.edit().remove("ab:$positionKey:$n:start").remove("ab:$positionKey:$n:end").apply(); activeSegment=-1; updateMarkers() }
            form.addView(row)
        }
        dialog.setOnDismissListener { optionsOpen=false; showControls() }; dialog.show()
    }
    override fun dispatchKeyEvent(event:KeyEvent):Boolean {
        val handledKeys=setOf(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_SPACE,KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,KeyEvent.KEYCODE_LEFT_BRACKET,KeyEvent.KEYCODE_RIGHT_BRACKET)
        if(!optionsOpen && event.keyCode in handledKeys && ::player.isInitialized) {
            if(event.action==KeyEvent.ACTION_DOWN) return onKeyDown(event.keyCode,event)
            if(event.action==KeyEvent.ACTION_UP) return true
        }
        return super.dispatchKeyEvent(event)
    }
    override fun onKeyDown(keyCode:Int,event:KeyEvent):Boolean {
        if(optionsOpen || !::player.isInitialized) return super.onKeyDown(keyCode,event)
        val ctrl=event.isCtrlPressed; val alt=event.isAltPressed; val shift=event.isShiftPressed
        val modifiers=listOf(ctrl,alt,shift).count { it }
        if(keyCode==KeyEvent.KEYCODE_LEFT_BRACKET || keyCode==KeyEvent.KEYCODE_RIGHT_BRACKET) {
            if(modifiers>1) return super.onKeyDown(keyCode,event)
            if(event.repeatCount==0) setSegment(if(alt) 1 else if(ctrl) 2 else if(shift) 3 else 0,keyCode==KeyEvent.KEYCODE_RIGHT_BRACKET)
            return true
        }
        when(keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if(modifiers>1) return super.onKeyDown(keyCode,event)
                val seconds=if(alt) altSeconds else if(ctrl) ctrlSeconds else if(shift) shiftSeconds else jumpSeconds
                jump(if(keyCode==KeyEvent.KEYCODE_DPAD_LEFT) -seconds else seconds); return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> { volumePercent(volumeStep); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { volumePercent(-volumeStep); return true }
            KeyEvent.KEYCODE_SPACE,KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if(event.repeatCount==0) togglePlaying(); return true }
        }
        return super.onKeyDown(keyCode,event)
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!ready) return
            if (!dragging && player.length > 0) seek.progress = (player.time * 1000 / player.length).toInt()
            clock.text = "${time(player.time)} / ${time(player.length)}"+if(segmentEnabled && activeSegment>=0) " · 구간 ${activeSegment+1} 반복" else ""
            updateMarkers()
            checkSegments(); if(SystemClock.elapsedRealtime()-lastSaveAt>=1000) { savePosition(); lastSaveAt=SystemClock.elapsedRealtime() }
            handler.postDelayed(this, 100)
        }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        loadSettings()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if(settings.contains("brightness")) window.attributes=window.attributes.apply { screenBrightness=settings.getFloat("brightness",.5f).coerceIn(.01f,1f) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setOnApplyWindowInsetsListener { view, insets -> @Suppress("DEPRECATION") view.setPadding(0,insets.systemWindowInsetTop,0,insets.systemWindowInsetBottom); insets } }
        viewport = FrameLayout(this).apply { clipChildren = true; setBackgroundColor(0xff000000.toInt()) }
        video = VLCVideoLayout(this)
        viewport.addView(video, FrameLayout.LayoutParams(-1, -1))
        root.addView(viewport, LinearLayout.LayoutParams(-1, 0, 1f))
        controlsOpacity=getPreferences(0).getInt("controlsOpacity",50).coerceIn(10,100)
        hideDelayMs=getPreferences(0).getLong("controlsHideMs",3000).coerceIn(100,60000)
        overlay=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; alpha=controlsOpacity/100f; setBackgroundColor(0xcc000000.toInt()) }
        viewport.addView(overlay,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        clock = TextView(this).apply { setTextColor(0xffffffff.toInt()) }
        overlay.addView(clock)
        seek = SegmentSeekBar(this).apply { max = 1000 }
        overlay.addView(seek)
        val controls = LinearLayout(this)
        fun button(label: String, action: () -> Unit) { controls.addView(Button(this).apply { text=label; setOnClickListener { action(); showControls() } }, LinearLayout.LayoutParams(-2, -2)) }
        button("이전") { change(-1) }
        button("재생/멈춤") { togglePlaying() }
        button("다음") { change(1) }
        button("배속") { val rates = floatArrayOf(.5f,.75f,1f,1.25f,1.5f,2f); AlertDialog.Builder(this).setItems(rates.map { "${it}×" }.toTypedArray()) { _, n -> player.rate = rates[n]; settings.edit().putFloat("playbackRate",rates[n]).apply() }.show() }
        button("자막") { subtitles() }
        button("디코딩") { AlertDialog.Builder(this).setTitle("디코딩 방식").setItems(arrayOf("자동", "소프트웨어 호환 모드")) { _,n -> savePosition(); softwareOnly=n==1; open() }.show() }
        button("화면 초기화") { scale=1f; video.scaleX=1f; video.scaleY=1f; video.translationX=0f; video.translationY=0f }
        button("현재 재생목록 보기") { currentQueue() }
        button("재생목록에 추가") { if(queueItems.isNotEmpty()) playlistFiles.showAdd(queueItems[index],queueItems) }
        button("랜덤") { shuffle=!shuffle; settings.edit().putBoolean("shuffle",shuffle).apply(); rebuildShuffle(); Toast.makeText(this,"랜덤 ${if(shuffle) "켜짐" else "꺼짐"}",Toast.LENGTH_SHORT).show() }
        button("반복") { repeatMode=(repeatMode+1)%3; settings.edit().putInt("repeatMode",repeatMode).apply(); Toast.makeText(this,listOf("반복 꺼짐","현재 영상 반복","전체 목록 반복")[repeatMode],Toast.LENGTH_SHORT).show() }
        button("구간 반복") { segmentOptions() }
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
        stateFileName=state?.getString("queueStateFile") ?: stateFileName
        val sessionName=state?.getString("queueStateFile") ?: intent.getStringExtra("queueFile")
        queueItems=when {
            sessionName!=null -> { require(sessionName.matches(Regex("queue-[a-f0-9-]+\\.json"))); File(cacheDir,sessionName).readText().let { Playlists.decode(it) } }
            else -> queue.map { QueueEntry(it,Uri.parse(it).lastPathSegment ?: "영상") }
        }
        queue=ArrayList(queueItems.map { it.id })
        index = (state?.getInt("queueIndex") ?: intent.getIntExtra("index", 0)).coerceIn(0, (queue.size-1).coerceAtLeast(0))
        temporary=state?.getBoolean("temporary") ?: intent.getBooleanExtra("temporary",true)
        queueTitle=state?.getString("queueTitle") ?: intent.getStringExtra("queueTitle") ?: "임시 재생목록"
        queueSort=state?.getInt("queueSort") ?: -1; queueDescending=state?.getBoolean("queueDescending") ?: false
        playlistFiles.restore(state)
        rebuildShuffle()
        player.setEventListener { event -> runOnUiThread {
            when(event.type) {
                MediaPlayer.Event.Playing -> { if (!ready) { ready=true; player.rate=settings.getFloat("playbackRate",1f); player.time=pendingSegmentStart ?: getPreferences(0).getLong(positionKey,0); pendingSegmentStart=null; handler.post(tick) } }
                MediaPlayer.Event.EndReached -> {
                    if(holdDirection==0) {
                        if(segmentEnabled && segments.any { it.valid() }) {
                            val valid=segments.indices.filter { segments[it].valid() }
                            activeSegment=valid[(valid.indexOf(activeSegment)+1).coerceAtLeast(0)%valid.size]
                            ready=false; handler.removeCallbacks(tick); val target=segments[activeSegment].start
                            player.stop(); player.play(); pendingSegmentStart=target
                        } else {
                            settings.edit().remove(positionKey).apply(); ready=false; handler.removeCallbacks(tick)
                            if(shuffle && repeatMode==2 && shuffleRemaining.isEmpty()) rebuildShuffle()
                            val next=PlaybackRules.next(index,queue.size,repeatMode,shuffle,shuffleRemaining)
                            if(next!=null) { history.add(index); shuffleRemaining.remove(next); index=next; open() }
                            else if(temporary) { queue.clear(); queueItems=emptyList(); finish() }
                        }
                    }
                }
                MediaPlayer.Event.EncounteredError -> showPlaybackError("E_VLC",if(positionKey.startsWith("smb:")) "NAS 영상 스트림을 재생하지 못했습니다. 연결·읽기 권한과 영상 형식을 확인하세요." else "파일 열기 단계 후 VLC 재생이 실패했습니다. 영상 형식·코덱을 확인하거나 소프트웨어 호환 모드를 시도하세요.")
            }
        } }
        var tapX=0f; var tapY=0f; var tapCandidate=false
        val slop=ViewConfiguration.get(this).scaledTouchSlop
        var previousSpan = 0f
        var focusX = 0f
        var focusY = 0f
        fun span(e: MotionEvent, a: Int=0, b: Int=1): Float = kotlin.math.hypot(e.getX(a)-e.getX(b), e.getY(a)-e.getY(b))
        val gestures=GestureDetector(this,object:GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e:MotionEvent)=true
            override fun onSingleTapUp(e:MotionEvent):Boolean { if(!touchConsumed) showControls(); return true }
            override fun onDoubleTap(e:MotionEvent):Boolean {
                touchConsumed=true
                when((e.x/viewport.width*3).toInt().coerceIn(0,2)) { 0->jump(-jumpSeconds); 1->togglePlaying(); 2->jump(jumpSeconds) }
                return true
            }
            override fun onLongPress(e:MotionEvent) { if(tapCandidate && !touchConsumed) beginHold(touchZone) }
        })
        viewport.setOnTouchListener { _, e ->
            if(e.actionMasked==MotionEvent.ACTION_DOWN) {
                touchConsumed=false; verticalGesture=false; touchZone=(e.x/viewport.width*3).toInt().coerceIn(0,2)
                initialVolume=audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                initialBrightness=window.attributes.screenBrightness.takeIf { it>=0 } ?: try { android.provider.Settings.System.getInt(contentResolver,android.provider.Settings.System.SCREEN_BRIGHTNESS)/255f } catch(_:Exception) { .5f }
            }
            gestures.onTouchEvent(e)
            when(e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { px=e.x; py=e.y; tapX=e.x; tapY=e.y; tapCandidate=true; previousSpan=0f }
                MotionEvent.ACTION_POINTER_DOWN -> if(e.pointerCount>=2) {
                    tapCandidate=false; touchConsumed=true; endHold(); previousSpan=span(e); focusX=(e.getX(0)+e.getX(1))/2; focusY=(e.getY(0)+e.getY(1))/2
                }
                MotionEvent.ACTION_MOVE -> {
                    if(kotlin.math.hypot(e.x-tapX,e.y-tapY)>slop) { tapCandidate=false; if(holdDirection!=0) endHold() }
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
                        val dy=tapY-e.y
                        if(scale<=1f && touchZone!=1 && (verticalGesture || kotlin.math.abs(dy)>slop*2 && kotlin.math.abs(dy)>kotlin.math.abs(e.x-tapX))) {
                            verticalGesture=true; touchConsumed=true; endHold()
                            val fraction=dy/viewport.height*1.5f
                            if(touchZone==0) {
                                window.attributes=window.attributes.apply { screenBrightness=(initialBrightness+fraction).coerceIn(.01f,1f) }
                                settings.edit().putFloat("brightness",window.attributes.screenBrightness).apply()
                                clock.text="밝기 ${(window.attributes.screenBrightness*100).toInt()}%"; showControls()
                            } else {
                                val max=audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                                audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC,(initialVolume+fraction*max).toInt().coerceIn(0,max),0)
                                clock.text="볼륨 ${audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)*100/max}%"; showControls()
                            }
                        }
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
                MotionEvent.ACTION_UP -> { endHold(); previousSpan=0f; if(tapCandidate) viewport.performClick(); tapCandidate=false }
                MotionEvent.ACTION_CANCEL -> { touchConsumed=true; endHold(); previousSpan=0f; tapCandidate=false }
            }; true
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, user: Boolean) {}
            override fun onStartTrackingTouch(s: SeekBar?) { dragging=true; handler.removeCallbacks(hideControls) }
            override fun onStopTrackingTouch(s: SeekBar?) { if(player.isSeekable) player.time=player.length*seek.progress/1000; dragging=false; showControls() }
        })
        open(); showControls()
    }
    override fun dispatchTouchEvent(event:MotionEvent):Boolean {
        if(::overlay.isInitialized && event.actionMasked==MotionEvent.ACTION_DOWN && overlay.visibility==View.VISIBLE) {
            val bounds=android.graphics.Rect()
            if(overlay.getGlobalVisibleRect(bounds) && bounds.contains(event.rawX.toInt(),event.rawY.toInt())) { controlTouchActive=true; handler.removeCallbacks(hideControls) }
        }
        val handled=super.dispatchTouchEvent(event)
        if(controlTouchActive && event.actionMasked in listOf(MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL)) { controlTouchActive=false; showControls() }
        return handled
    }
    private fun currentQueue() {
        if(queueItems.isEmpty()) return
        handler.removeCallbacks(hideControls); optionsOpen=true
        val form=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        val sort=Button(this).apply { text=if(queueSort<0) "정렬: 원래 목록 순서" else "정렬: ${listOf("이름","크기","날짜")[queueSort]} ${if(queueDescending) "내림차순" else "오름차순"}" }
        form.addView(sort)
        val list=ListView(this)
        list.adapter=ArrayAdapter(this,android.R.layout.simple_list_item_activated_1,queueItems.mapIndexed { n,item -> "${if(n==index) "▶ " else ""}${item.name}\n${item.size/1_000_000}MB · ${if(item.date>0) java.text.SimpleDateFormat("yyyy-MM-dd",java.util.Locale.US).format(java.util.Date(item.date)) else "날짜 알 수 없음"}" })
        list.choiceMode=ListView.CHOICE_MODE_SINGLE; list.setItemChecked(index,true)
        form.addView(list,LinearLayout.LayoutParams(-1,(resources.displayMetrics.heightPixels*.55).toInt()))
        val dialog=AlertDialog.Builder(this).setTitle("$queueTitle · ${queueItems.size}개").setView(form).setPositiveButton("닫기",null).setNeutralButton("저장한 목록 불러오기") { _,_ -> playlistFiles.find() }.create()
        queueDialog=dialog
        dialog.setOnDismissListener { optionsOpen=false; showControls() }
        list.setOnItemClickListener { _,_,at,_ -> dialog.dismiss(); if(at!=index) { savePosition(); index=at; shuffleRemaining.remove(at); open() } }
        sort.setOnClickListener { AlertDialog.Builder(this).setTitle("정렬 · 같은 항목을 다시 누르면 역순").setItems(arrayOf("이름순","크기순","날짜순")) { _,key ->
            val current=queue[index]
            queueDescending=if(key==queueSort) !queueDescending else false; queueSort=key
            queueItems=QueueModel.sorted(queueItems,key,queueDescending); queue=ArrayList(queueItems.map { it.id }); index=queue.indexOf(current); history.clear(); rebuildShuffle()
            dialog.dismiss(); currentQueue()
        }.show() }
        dialog.setOnShowListener { list.post { list.setSelectionFromTop(index,(resources.displayMetrics.heightPixels*.55/2-36*resources.displayMetrics.density).toInt().coerceAtLeast(0)) } }
        dialog.show()
    }
    override fun onSaveInstanceState(out:Bundle) {
        super.onSaveInstanceState(out)
        File(cacheDir,stateFileName).writeText(Playlists.encode(queueItems)); out.putString("queueStateFile",stateFileName); out.putInt("queueIndex",index); out.putBoolean("temporary",temporary); out.putString("queueTitle",queueTitle); out.putInt("queueSort",queueSort); out.putBoolean("queueDescending",queueDescending); playlistFiles.saveState(out)
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
        fun numeric(label:String,value:String):EditText {
            form.addView(TextView(this).apply { text=label })
            return EditText(this).apply { inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL; setText(value); form.addView(this) }
        }
        val jump=numeric("두 번 탭 / ← → 이동 (1~600초)",jumpSeconds.toString())
        val alt=numeric("Alt + ← → (1~600초)",altSeconds.toString())
        val ctrl=numeric("Ctrl + ← → (1~600초)",ctrlSeconds.toString())
        val shift=numeric("Shift + ← → (1~600초)",shiftSeconds.toString())
        val volume=numeric("↑ ↓ 볼륨 변화 (1~100%, 기기 볼륨 단계로 반올림)",volumeStep.toString())
        val hold=numeric("좌우 길게 누르기 · 현재 배속의 배율 (1~16배)",holdMultiplier.toString())
        val random=CheckBox(this).apply { text="랜덤 재생"; isChecked=shuffle }; form.addView(random)
        val repeat=Spinner(this).apply { adapter=ArrayAdapter(this@PlayerActivity,android.R.layout.simple_spinner_dropdown_item,listOf("반복 꺼짐","현재 영상 한 개 반복","전체 목록 반복")); setSelection(repeatMode) }; form.addView(repeat)
        form.addView(TextView(this).apply { text="왼쪽/가운데/오른쪽 두 번 탭: 뒤로/재생·멈춤/앞으로\n왼쪽 세로 스와이프: 밝기 · 오른쪽: 볼륨\n확대 중 한 손가락 이동: 화면 이동\nSpace: 재생·멈춤 · [, ]: 구간 1 · Alt/Ctrl/Shift 조합: 구간 2/3/4\n좌우 Alt/Ctrl/Shift 모두 지원" })
        val dialog=AlertDialog.Builder(this).setTitle("재생 컨트롤 옵션").setView(ScrollView(this).apply { addView(form) }).setNegativeButton("취소",null).setPositiveButton("저장",null).create()
        controlsDialog=dialog
        dialog.setOnDismissListener { optionsOpen=false; showControls() }
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value=delay.text.toString().replace(',','.').toBigDecimalOrNull()
            if(value==null || value<java.math.BigDecimal("0.1") || value>java.math.BigDecimal("60.0") || value.stripTrailingZeros().scale()>1) { delay.error="0.1~60.0초를 0.1초 단위로 입력하세요"; return@setOnClickListener }
            fun integer(field:EditText,max:Int):Int? {
                val n=field.text.toString().toIntOrNull()
                if(n==null || n !in 1..max) { field.error="1~$max 정수로 입력하세요"; return null }; return n
            }
            val j=integer(jump,600) ?: return@setOnClickListener
            val a=integer(alt,600) ?: return@setOnClickListener
            val c=integer(ctrl,600) ?: return@setOnClickListener
            val sh=integer(shift,600) ?: return@setOnClickListener
            val v=integer(volume,100) ?: return@setOnClickListener
            val h=hold.text.toString().replace(',','.').toFloatOrNull()
            if(h==null || !h.isFinite() || h<1f || h>16f) { hold.error="1~16배로 입력하세요"; return@setOnClickListener }
            jumpSeconds=j; altSeconds=a; ctrlSeconds=c; shiftSeconds=sh; volumeStep=v; holdMultiplier=h
            val changed=shuffle!=random.isChecked; shuffle=random.isChecked; repeatMode=repeat.selectedItemPosition
            if(changed) rebuildShuffle()
            settings.edit().putInt("jumpSeconds",j).putInt("altSeconds",a).putInt("ctrlSeconds",c).putInt("shiftSeconds",sh).putInt("volumeStep",v).putFloat("holdMultiplier",h).putBoolean("shuffle",shuffle).putInt("repeatMode",repeatMode).apply()
            controlsOpacity=opacity.progress+10; hideDelayMs=value.multiply(java.math.BigDecimal(1000)).toLong()
            overlay.alpha=controlsOpacity/100f
            getPreferences(0).edit().putInt("controlsOpacity",controlsOpacity).putLong("controlsHideMs",hideDelayMs).apply()
            dialog.dismiss()
        } }
        dialog.show()
    }
    private fun clampPan() { val x=viewport.width*(scale-1)/2; val y=viewport.height*(scale-1)/2; video.translationX=video.translationX.coerceIn(-x,x); video.translationY=video.translationY.coerceIn(-y,y) }
    private fun open() {
        endHold()
        val generation=++inputGeneration
        ready=false; handler.removeCallbacks(tick); errorDialog?.dismiss()
        player.stop(); activeInput?.close(); activeInput=null; bridge?.close(); bridge=null
        positionKey=queue[index]
        pendingSegmentStart=null; loadSegments()
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
        lastPlaybackError="오류 $code\n$reason\n\n입력: ${source.scheme ?: "unknown"} / $inputMode\n문서 제공자: ${if(source.scheme=="content") source.authority else "해당 없음"}\n디코딩: ${if(softwareOnly) "소프트웨어" else "자동"}\n기기: ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n앱: 0.1.4 / LibVLC 3.7.7"
        File(filesDir,"playback-error.txt").writeText(lastPlaybackError)
        errorDialog?.dismiss()
        errorDialog=AlertDialog.Builder(this).setTitle("재생 실패").setMessage(lastPlaybackError)
            .setPositiveButton("닫기",null)
            .setNeutralButton("진단정보 복사") { _,_ -> getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("재생 진단",lastPlaybackError)); Toast.makeText(this,"진단정보를 복사했습니다",Toast.LENGTH_SHORT).show() }
            .setNegativeButton(if(softwareOnly) "자동 모드 재시도" else "소프트웨어 재시도") { _,_ -> softwareOnly=!softwareOnly; open() }.show()
    }
    private fun change(delta: Int) {
        val next=if(shuffle) {
            if(delta<0) { if(history.isEmpty()) return else history.removeAt(history.lastIndex) }
            else { if(shuffleRemaining.isEmpty()) { if(repeatMode!=2) return; rebuildShuffle() }; shuffleRemaining.firstOrNull() ?: index }
        } else (index+delta).let { if(it in queue.indices) it else if(repeatMode==2) (it+queue.size)%queue.size else return }
        if(delta>0) history.add(index)
        shuffleRemaining.remove(next); savePosition(); index=next; open()
    }
    private fun savePosition() { if(ready && player.time>0) getPreferences(0).edit().putLong(positionKey,player.time).apply() }
    private fun subtitles() {
        val tracks=player.spuTracks.orEmpty()
        AlertDialog.Builder(this).setTitle("자막").setItems((listOf("외부 자막 파일 열기", "자막 끄기")+tracks.map { it.name }).toTypedArray()) { _, n ->
            when(n) { 0 -> startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type="*/*"; addCategory(Intent.CATEGORY_OPENABLE) },10); 1 -> player.setSpuTrack(-1); else -> player.setSpuTrack(tracks[n-2].id) }
        }.show()
    }
    @Deprecated("Activity result") override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request,result,data)
        if(playlistFiles.result(request,result,data)) { if(resumeAfterPicker && ready) player.play(); resumeAfterPicker=false; return }
        if(request==10 && result==RESULT_OK) data?.data?.let { uri ->
            // VLC can read private file paths; copy content-provider subtitles into a private cache file.
            Thread { try { val file=File(cacheDir,"external-subtitle.srt"); contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }; runOnUiThread { player.addSlave(org.videolan.libvlc.interfaces.IMedia.Slave.Type.Subtitle,Uri.fromFile(file),true) } } catch(e: Exception) { runOnUiThread { Toast.makeText(this,e.message,Toast.LENGTH_LONG).show() } } }.start()
        }
    }
    override fun onPause() { endHold(); resumeAfterPicker=::player.isInitialized && player.isPlaying; savePosition(); if(::player.isInitialized) player.pause(); super.onPause() }
    override fun onDestroy() { inputGeneration++; queueDialog?.dismiss(); if(isFinishing) { File(cacheDir,stateFileName).delete(); queue.clear(); queueItems=emptyList(); intent.getStringExtra("queueFile")?.takeIf { it.matches(Regex("queue-[a-f0-9-]+\\.json")) }?.let { File(cacheDir,it).delete() } }; controlsDialog?.dismiss(); errorDialog?.dismiss(); handler.removeCallbacksAndMessages(null); if(::player.isInitialized) { player.stop(); player.detachViews(); player.release(); vlc.release() }; activeInput?.close(); bridge?.close(); super.onDestroy() }
    private fun time(ms: Long): String { val s=(ms/1000).coerceAtLeast(0); return "%d:%02d:%02d".format(s/3600,s/60%60,s%60) }
}



