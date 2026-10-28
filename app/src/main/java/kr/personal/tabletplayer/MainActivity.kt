package kr.personal.tabletplayer

import android.app.*
import android.content.*
import android.net.Uri
import android.os.*
import android.text.InputType
import android.view.*
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.text.SimpleDateFormat
import java.util.Locale

data class Row(val node:Node,val name:String=node.name,val dir:Boolean=node.directory,val size:Long=if(dir) 0 else node.size,val date:Long=node.modified) {
    fun snapshot()=Snapshot(node,name,size,date)
}
class MainActivity: Activity() {
    private var openedUri:String?=null
    private val playlistFiles by lazy { PlaylistFiles(this) { items,index,title -> launchQueue(items,index,false,title) } }
    private val executor=Executors.newSingleThreadExecutor()
    private var cancel=AtomicBoolean(false)
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private val prefs by lazy { getSharedPreferences("library",0) }
    private val roots=mutableListOf<Pair<String,Node>>()
    private val stack=mutableListOf<Pair<String,Node>>()
    private var rows=listOf<Row>()
    private val selected=linkedMapOf<String,Snapshot>()
    private var sort=0
    private var preview=false
    private var destinationMode=false
    private var pending=listOf<Snapshot>()
    private var moving=false
    private var busy=false
    private var returnStack=listOf<Pair<String,Node>>()
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        openedUri=state?.getString("openedUri"); playlistFiles.restore(state)
        contentResolver.persistedUriPermissions.filter { it.isReadPermission }.forEach { grant -> try { roots.add((prefs.getString("label:${grant.uri}",null) ?: "연결된 저장소") to Storage.tree(this,grant.uri)) } catch(_:Exception) {} }
        home()
        if(state==null && !prefs.getBoolean("setupShown",false)) {
            prefs.edit().putBoolean("setupShown",true).apply()
            AlertDialog.Builder(this).setTitle("영상 폴더 연결")
                .setMessage("자주 사용하는 영상 폴더를 한 번 연결하면 다음부터 같은 폴더의 영상을 바로 목록으로 재생합니다. SD·USB는 연결 후 각각 허용하고 NAS는 별도로 로그인하세요.")
                .setPositiveButton("영상 폴더 연결") { _,_ -> askStorage() }.setNegativeButton("나중에",null).show()
        }
    }
    private fun page(title:String) {
        val outer=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(20,12,20,12) }
        outer.setOnApplyWindowInsetsListener { view, insets -> @Suppress("DEPRECATION") view.setPadding(20,insets.systemWindowInsetTop+12,20,insets.systemWindowInsetBottom+12); insets }
        outer.addView(TextView(this).apply { text=title; textSize=21f; setPadding(0,8,0,12) })
        status=TextView(this).apply { text=if(busy) "작업 중…" else "" }; outer.addView(status)
        body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        outer.addView(ScrollView(this).apply { addView(body) },LinearLayout.LayoutParams(-1,0,1f))
        setContentView(outer)
    }
    private fun button(text:String,action:()->Unit) { body.addView(Button(this).apply { this.text=text; isAllCaps=false; setOnClickListener { if(!busy) action() } }) }
    private fun note(text:String) { body.addView(TextView(this).apply { this.text=text; setPadding(4,10,4,10) }) }
    private fun fail(e:Throwable) { if(!isFinishing) AlertDialog.Builder(this).setTitle("확인 필요").setMessage(e.message ?: e.javaClass.simpleName).setPositiveButton("닫기",null).show() }
    private fun <T> background(message:String,job:()->T,done:(T)->Unit) {
        if(busy) return
        busy=true; cancel=AtomicBoolean(false); status.text=message
        val progress=ProgressDialog(this).apply { setMessage(message); setCancelable(false); setButton(DialogInterface.BUTTON_NEGATIVE,"취소") { _,_ -> cancel.set(true) }; show() }
        executor.submit { try { val result=job(); runOnUiThread { busy=false; progress.dismiss(); if(!isFinishing) done(result) } } catch(e:Exception) { runOnUiThread { busy=false; progress.dismiss(); status.text="중단됨"; fail(e) } } }
    }
    private fun home() {
        page(if(destinationMode) "직접 선택 · 목적지 저장소" else "태블릿 플레이어")
        if(destinationMode) button("목적지 선택 취소") { exitDestination() }
        roots.forEach { (label,node) -> button("📁 $label") { stack.clear(); stack.add(label to node); load() } }
        button("내부 저장소 / SD / USB 연결") { askStorage() }
        button("NAS 연결 (SMB)") { nas() }
        if(!destinationMode) {
            button("동영상 파일 열기") { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type="video/*"; addCategory(Intent.CATEGORY_OPENABLE); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) },2) }
            button("저장한 재생목록 파일 열기") { playlistFiles.find() }
            button("기존 재생목록") { playlist() }
            button("작업 로그") { logs() }
            note("처음에는 저장소별로 폴더 접근을 허용하세요. Android 제한으로 내부 저장소 루트나 Download 루트 대신 Movies 등 하위 폴더를 선택해야 할 수 있습니다. SD·USB는 연결된 경우 시스템 선택기에 나타납니다.")
            note("실험용 개인 앱 0.1 · NAS 암호는 앱 실행 중에만 유지됩니다. 이동·삭제 전 테스트 파일로 확인하세요.")
        }
    }
    private fun askStorage() {
        val label=EditText(this).apply { hint="예: 내부 Movies / SD 영상 / USB" }
        AlertDialog.Builder(this).setTitle("저장소 표시 이름").setView(label).setPositiveButton("폴더 연결") { _,_ ->
            prefs.edit().putString("newLabel",label.text.toString().ifBlank { "연결된 저장소" }).apply()
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION).apply {
                prefs.getString("lastVideoFolder",null)?.let { putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,Uri.parse(it)) }
            },1)
        }.setNegativeButton("취소",null).show()
    }
    private fun load() {
        val current=stack.lastOrNull() ?: return home()
        preview=false; selected.clear()
        background("폴더 읽는 중",{ current.second.children().map { Row(it) } }) { rows=it; render() }
    }
    private fun render() {
        page((if(destinationMode) "목적지 · " else if(preview) "검색 미리보기 · " else "")+stack.joinToString(" / ") { it.first })
        button("상위 폴더 / 저장소 목록") { if(stack.size>1) { stack.removeAt(stack.lastIndex); load() } else { stack.clear(); home() } }
        if(destinationMode) {
            button("이 폴더 선택") { validateDestination(stack.last().second,stack.last().first) }
            button("새 폴더") { newFolder() }
            button("목적지 선택 취소") { exitDestination() }
        } else {
            button("정렬: ${listOf("이름","크기 큰 순","최근 날짜 순")[sort]}") { sort=(sort+1)%3; render() }
            button("고급 검색 · 기본 500MB 이상") { searchDialog() }
            button("${selected.size}개 선택 · 전체 선택/해제") { val files=rows.filter { !it.dir }; if(selected.size==files.size) selected.clear() else files.forEach { selected[it.node.id]=it.snapshot() }; render() }
            if(rows.any { !it.dir }) {
                button("선택 파일 복사") { chooseDestination(false) }
                button("선택 파일 이동") { chooseDestination(true) }
                button("선택 파일 삭제") { confirmDelete() }
                button("재생목록에 추가") { addPlaylist() }
                button("선택 파일 순서대로 재생") { play(selected.keys.toList()) }
            }
            if(preview) note("검색은 파일을 변경하지 않습니다. 아래에서 개별 선택 후 작업을 따로 실행하세요. ${rows.size}개 결과")
        }
        val sorted=when(sort) { 1->rows.sortedWith(compareBy<Row> { !it.dir }.thenByDescending { it.size }); 2->rows.sortedWith(compareBy<Row> { !it.dir }.thenByDescending { it.date }); else->rows.sortedWith(compareBy<Row> { !it.dir }.thenBy { it.name.lowercase() }) }
        sorted.filter { !destinationMode || it.dir }.forEach { row ->
            if(row.dir) button("📁 ${row.name}") { stack.add(row.name to row.node); load() }
            else {
                val line=LinearLayout(this)
                line.addView(CheckBox(this).apply { isChecked=selected.containsKey(row.node.id); setOnCheckedChangeListener { _,checked -> if(checked) selected[row.node.id]=row.snapshot() else selected.remove(row.node.id); status.text="${selected.size}개 선택 · 위 선택 메뉴를 눌러 작업" } })
                line.addView(Button(this).apply { isAllCaps=false; text="${row.name}\n${row.size/1_000_000}MB · ${date(row.date)}"; setOnClickListener { if(!busy) playFolder(row.node.id,stack.last().second) } },LinearLayout.LayoutParams(0,-2,1f))
                body.addView(line)
            }
        }
        if(!destinationMode) button("선택 작업 메뉴 갱신 (${selected.size}개)") { render() }
        if(rows.isEmpty()) note("폴더가 비어 있습니다")
    }
    private fun searchDialog() {
        val form=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(24,8,24,8) }
        val recursive=CheckBox(this).apply { text="하위 폴더 포함"; isChecked=true }; form.addView(recursive)
        val videos=CheckBox(this).apply { text="동영상만"; isChecked=true }; form.addView(videos)
        fun field(hint:String,value:String=""):EditText=EditText(this).apply { this.hint=hint; setText(value); form.addView(this) }
        val min=field("최소 MB (십진 MB)","500"); val max=field("최대 MB · 빈칸은 제한 없음")
        min.inputType=InputType.TYPE_CLASS_NUMBER; max.inputType=InputType.TYPE_CLASS_NUMBER
        val after=field("시작 날짜 yyyy-MM-dd · 빈칸은 조건 없음"); val before=field("종료 날짜 yyyy-MM-dd · 빈칸은 조건 없음")
        AlertDialog.Builder(this).setTitle("고급 검색").setView(form).setPositiveButton("검색 미리보기") { _,_ -> try {
            fun bytes(e:EditText):Long?=e.text.toString().trim().takeIf { it.isNotEmpty() }?.toLong()?.let { Math.multiplyExact(it,1_000_000L) }
            fun day(e:EditText,end:Boolean):Long?=e.text.toString().trim().takeIf { it.isNotEmpty() }?.let { text ->
                val format=SimpleDateFormat("yyyy-MM-dd",Locale.US).apply { isLenient=false }; require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(text)); val start=format.parse(text)!!.time
                if(end) java.util.Calendar.getInstance().apply { timeInMillis=start; add(java.util.Calendar.DATE,1) }.timeInMillis-1 else start
            }
            val filter=SearchFilter(recursive.isChecked,videos.isChecked,bytes(min) ?: 0,bytes(max),day(after,false),day(before,true))
            background("검색 중 · 원본 변경 없음",{ FileTasks.search(stack.last().second,filter,cancel) {} }) { result -> selected.clear(); rows=result.map { Row(it.node,it.name,false,it.size,it.modified) }; preview=true; render() }
        } catch(e:Exception) { fail(e) } }.setNegativeButton("취소",null).show()
    }
    private fun chooseDestination(move:Boolean) {
        if(selected.isEmpty()) { Toast.makeText(this,"먼저 파일을 선택하세요",Toast.LENGTH_SHORT).show(); return }
        pending=selected.values.toList(); moving=move; returnStack=stack.toList()
        val recent=recent()
        AlertDialog.Builder(this).setTitle("${if(move) "이동" else "복사"} 목적지 · 최근 3개").setItems((recent.map { it.second }+"직접 선택").toTypedArray()) { _,n ->
            if(n==recent.size) { destinationMode=true; stack.clear(); home() }
            else background("최근 목적지 확인",{ Storage.resolve(this,recent[n].first) }) { validateDestination(it,recent[n].second) }
        }.setNegativeButton("취소",null).show()
    }
    private fun recent():List<Pair<String,String>> { val array=JSONArray(prefs.getString("recent","[]")); return (0 until array.length()).map { array.getJSONObject(it).let { o->o.getString("id") to o.getString("label") } }.distinctBy { it.first }.take(3) }
    private fun remember(node:Node,label:String) { val list=(listOf(node.id to label)+recent()).distinctBy { it.first }.take(3); val array=JSONArray(); list.forEach { array.put(JSONObject().put("id",it.first).put("label",it.second)) }; prefs.edit().putString("recent",array.toString()).apply() }
    private fun validateDestination(node:Node,label:String) {
        background("권한·중복·용량 확인",{ FileTasks.preflight(pending,node,moving) }) { report ->
            AlertDialog.Builder(this).setTitle("최종 확인 · $label").setMessage("$report\n\n${pending.take(8).joinToString("\n") { it.name }}${if(pending.size>8) "\n외 ${pending.size-8}개" else ""}\n\n실행 중 앱을 종료하지 마세요.").setPositiveButton("검증 후 실행") { _,_ ->
                remember(node,label)
                background("복사 및 내용 검증 중",{ FileTasks.transfer(pending,node,moving,cancel) { appendLog(it) } }) { destinationMode=false; stack.clear(); stack.addAll(returnStack); pending=emptyList(); selected.clear(); logs() }
            }.setNegativeButton("취소",null).show()
        }
    }
    private fun exitDestination() { destinationMode=false; stack.clear(); stack.addAll(returnStack); pending=emptyList(); selected.clear(); if(stack.isEmpty()) home() else load() }
    private fun newFolder() {
        val input=EditText(this).apply { hint="새 폴더 이름" }
        AlertDialog.Builder(this).setTitle("새 폴더").setView(input).setPositiveButton("만들기") { _,_ -> background("폴더 생성",{ stack.last().second.create(input.text.toString(),true) }) { load() } }.setNegativeButton("취소",null).show()
    }
    private fun confirmDelete() {
        val items=selected.values.toList()
        if(items.isEmpty()) return
        AlertDialog.Builder(this).setTitle("${items.size}개 파일 영구 삭제").setMessage("휴지통과 되돌리기가 없습니다.\n\n"+items.take(10).joinToString("\n") { it.name }).setPositiveButton("원본 영구 삭제") { _,_ ->
            background("삭제 중",{ for(item in items) { if(cancel.get()) break; try { require(item.unchanged() && item.node.writable()) { "원본 변경 또는 권한 없음" }; appendLog("삭제 시작: ${item.name}"); require(item.node.delete()) { "삭제 실패" }; appendLog("삭제 완료: ${item.name}") } catch(e:Exception) { appendLog("삭제 중단: ${item.name} · ${e.message}"); throw e } } }) { selected.clear(); load() }
        }.setNegativeButton("취소",null).show()
    }
    private fun nas() {
        val form=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(24,8,24,8) }
        fun input(hint:String)=EditText(this).apply { this.hint=hint; inputType=InputType.TYPE_CLASS_TEXT; form.addView(this) }
        val host=input("서버 IP 또는 호스트 이름"); val share=input("공유 폴더 이름"); val domain=input("도메인 · 선택"); val user=input("사용자 이름 · 게스트는 빈칸"); val password=input("암호").apply { inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        AlertDialog.Builder(this).setTitle("NAS · SMB2/3 연결").setView(form).setPositiveButton("연결") { _,_ ->
            val h=host.text.toString().trim(); val s=share.text.toString().trim(); val d=domain.text.toString(); val u=user.text.toString(); val p=password.text.toString(); password.text.clear()
            background("NAS 연결 중",{ Storage.connect(h,s,d,u,p) }) { root -> roots.removeAll { it.second.id==root.id }; roots.add("NAS $h / $s" to root); home() }
        }.setNegativeButton("취소",null).show()
    }
    private fun savedQueue():List<Pair<String,String>> { val a=JSONArray(prefs.getString("playlist","[]")); return (0 until a.length()).map { a.getJSONObject(it).let { o->o.getString("id") to o.getString("name") } } }
    private fun saveQueue(items:List<Pair<String,String>>) { val a=JSONArray(); items.forEach { a.put(JSONObject().put("id",it.first).put("name",it.second)) }; prefs.edit().putString("playlist",a.toString()).apply() }
    private fun addPlaylist() { saveQueue((savedQueue()+selected.values.map { it.node.id to it.name }).distinctBy { it.first }.take(100)); Toast.makeText(this,"재생목록에 저장했습니다 (최대 100개)",Toast.LENGTH_LONG).show() }
    private fun playlist() {
        page("재생목록 · 저장된 순서")
        button("저장소 목록") { home() }
        val queue=savedQueue()
        button("전체 순서대로 재생") { play(queue.map { it.first }) }
        queue.forEachIndexed { index,item ->
            button(item.second) { play(queue.map { it.first },index) }
            button("↑ 위로 / 목록에서 제거 · ${item.second}") { AlertDialog.Builder(this).setItems(arrayOf("위로 이동","목록에서 제거")) { _,action -> val list=queue.toMutableList(); if(action==0 && index>0) java.util.Collections.swap(list,index,index-1) else if(action==1) list.removeAt(index); saveQueue(list); playlist() }.show() }
        }
    }
    private fun play(ids:List<String>,index:Int=0) {
        if(ids.isEmpty()) return
        background("재생목록 준비",{ ids.map { Playlists.entry(this,it) } }) { launchQueue(it,index,false,"선택 재생목록") }
    }
    private fun launchQueue(items:List<QueueEntry>,index:Int,temporary:Boolean,title:String) {
        if(items.isEmpty()) return
        // Metadata travels through a private cache file to avoid Android's Binder size limit.
        val session=File(cacheDir,"queue-${java.util.UUID.randomUUID()}.json").apply { writeText(Playlists.encode(items)) }
        val launch=Intent(this,PlayerActivity::class.java).putExtra("queueFile",session.name).putExtra("index",index.coerceIn(0,items.lastIndex)).putExtra("temporary",temporary).putExtra("queueTitle",title)
        startActivity(launch)
    }
    private fun playFolder(id:String,parent:Node) {
        prefs.edit().putString("lastVideoFolder",parent.id).apply()
        background("폴더의 영상과 하위 폴더 확인 중",{ Playlists.scan(parent) { cancel.get() } }) { scan ->
            val direct=scan.direct.toMutableList()
            var at=direct.indexOfFirst { Playlists.sameDocument(it.id,id) }
            if(at<0) {
                val original=Playlists.entry(this,id)
                val matches=direct.withIndex().filter { it.value.name==original.name && it.value.size==original.size && original.size>0 }
                if(matches.size==1) at=matches.single().index
            }
            val chosen=direct.getOrNull(at)?.id ?: id
            if(at<0) { fail(IllegalStateException("선택한 영상이 이 폴더에 없습니다. 영상이 들어 있는 폴더를 선택하세요")); return@background }
            fun start(extra:List<QueueEntry>) { val all=QueueModel.unique(direct+extra); launchQueue(all,all.indexOfFirst { it.id==chosen }.coerceAtLeast(0),true,"임시 재생목록") }
            if(scan.folders.isEmpty()) { start(emptyList()); return@background }
            val checked=BooleanArray(scan.folders.size)
            AlertDialog.Builder(this).setTitle("영상을 추가할 하위 폴더 선택")
                .setMultiChoiceItems(scan.folders.map { "${it.label} (${it.videos.size}개·하위 포함)" }.toTypedArray(),checked) { _,n,value -> checked[n]=value }
                .setPositiveButton("선택 완료") { _,_ ->
                    val folders=scan.folders.filterIndexed { n,_->checked[n] }
                    if(folders.isEmpty()) start(emptyList()) else AlertDialog.Builder(this).setTitle("선택한 폴더를 추가할까요?").setMessage(folders.joinToString("\n") { it.label })
                        .setPositiveButton("추가") { _,_ -> start(folders.flatMap { it.videos }) }.setNegativeButton("추가 없이 재생") { _,_ -> start(emptyList()) }.setCancelable(false).show()
                }.setNegativeButton("현재 폴더만 재생") { _,_ -> start(emptyList()) }.setCancelable(false).show()
        }
    }
    private fun openedFile(id:String) {
        background("영상 폴더 권한 확인",{ Playlists.parent(this,id) }) { parent ->
            if(parent!=null) playFolder(id,parent) else {
                openedUri=id
                // Go straight to the permission picker, starting at the video's parent.
                val picker=Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                val initial=Playlists.parentHint(this,id) ?: prefs.getString("lastVideoFolder",null)?.let(Uri::parse)
                if(initial!=null) picker.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,initial)
                Toast.makeText(this,"영상 폴더를 한 번 허용하세요. 취소하면 선택한 영상만 재생합니다",Toast.LENGTH_LONG).show()
                startActivityForResult(picker,4)
            }
        }
    }
    override fun onSaveInstanceState(out:Bundle) { super.onSaveInstanceState(out); out.putString("openedUri",openedUri); playlistFiles.saveState(out) }
    private fun appendLog(text:String) { File(filesDir,"operations.log").appendText("${SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US).format(java.util.Date())} $text\n") }
    private fun logs() { page("작업 로그"); button("저장소 목록") { home() }; button("로그 내보내기") { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply { type="text/plain"; putExtra(Intent.EXTRA_TITLE,"tabletplayer-log.txt") },3) }; note(File(filesDir,"operations.log").takeIf { it.exists() }?.readText()?.takeLast(60000) ?: "작업 기록이 없습니다") }
    private fun date(value:Long)=if(value<=0) "날짜 알 수 없음" else SimpleDateFormat("yyyy-MM-dd",Locale.US).format(java.util.Date(value))
    @Deprecated("Activity result") override fun onActivityResult(request:Int,result:Int,data:Intent?) {
        super.onActivityResult(request,result,data)
        if(playlistFiles.result(request,result,data)) return
        if(result!=RESULT_OK) {
            if(request==4) openedUri?.let { id -> openedUri=null; background("선택 영상 준비",{ Playlists.entry(this,id) }) { launchQueue(listOf(it),0,true,"임시 재생목록") } }
            return
        }
        val uri=data?.data ?: return
        try { when(request) {
            1 -> { contentResolver.takePersistableUriPermission(uri,if(data.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0) Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION else Intent.FLAG_GRANT_READ_URI_PERMISSION); val label=prefs.getString("newLabel","연결된 저장소")!!; prefs.edit().putString("label:$uri",label).apply(); val node=Storage.tree(this,uri); roots.removeAll { it.second.id==node.id }; roots.add(label to node); home() }
            2 -> { try { contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch(_:Exception) {}; openedFile(uri.toString()) }
            4 -> { contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION); val id=openedUri; openedUri=null; if(id!=null) playFolder(id,Storage.tree(this,uri)) }
            3 -> background("로그 저장",{ contentResolver.openOutputStream(uri)!!.use { it.write((File(filesDir,"operations.log").takeIf { f->f.exists() }?.readText() ?: "기록 없음").toByteArray()) } }) { Toast.makeText(this,"저장했습니다",Toast.LENGTH_SHORT).show() }
        } } catch(e:Exception) { fail(e) }
    }
    @Deprecated("Back navigation") override fun onBackPressed() { if(busy) { Toast.makeText(this,"작업 창의 취소를 사용하세요",Toast.LENGTH_SHORT).show(); return }; if(destinationMode) exitDestination() else if(stack.isNotEmpty()) { if(stack.size>1) { stack.removeAt(stack.lastIndex); load() } else { stack.clear(); home() } } else super.onBackPressed() }
    override fun onDestroy() { cancel.set(true); executor.shutdown(); super.onDestroy() }
}


