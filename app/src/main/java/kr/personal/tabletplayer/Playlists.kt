package kr.personal.tabletplayer

import android.app.*
import android.content.*
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object Playlists {
    const val MAX_ENTRIES=10000
    fun encode(items:List<QueueEntry>):String=JSONArray().apply { items.forEach { put(JSONObject().put("id",it.id).put("name",it.name).put("size",it.size).put("date",it.date)) } }.toString()
    fun decode(text:String):List<QueueEntry> {
        val a=JSONArray(text); require(a.length()<=MAX_ENTRIES) { "재생목록은 최대 10,000개입니다" }
        return QueueModel.unique((0 until a.length()).map { a.getJSONObject(it).let { o ->
            val id=o.getString("id"); require(Uri.parse(id).scheme in listOf("content","smb","file","http","https")) { "지원하지 않는 영상 주소" }
            QueueEntry(id,o.getString("name"),o.optLong("size"),o.optLong("date"))
        } })
    }
    fun entry(context:Context,id:String):QueueEntry {
        val uri=Uri.parse(id)
        if(uri.scheme=="file") return File(uri.path ?: "").let { QueueEntry(id,it.name,it.length(),it.lastModified()) }
        val node=Storage.resolve(context,id)
        val name=try { node.name } catch(_:Exception) { uri.lastPathSegment ?: "영상" }
        val size=try { node.size } catch(_:Exception) { 0L }
        val date=try { node.modified } catch(_:Exception) { 0L }
        return QueueEntry(id,name,size,date)
    }
    fun sameDocument(a:String,b:String):Boolean {
        if(a==b) return true
        return try { val x=Uri.parse(a); val y=Uri.parse(b); x.authority==y.authority && DocumentsContract.getDocumentId(x)==DocumentsContract.getDocumentId(y) } catch(_:Exception) { false }
    }
    fun parent(context:Context,id:String):Node? {
        val uri=Uri.parse(id)
        if(uri.scheme=="smb") return Storage.resolve(context,id.trimEnd('/').substringBeforeLast('/')+"/")
        if(uri.scheme!="content" || !DocumentsContract.isDocumentUri(context,uri)) return null
        val document=DocumentsContract.getDocumentId(uri)
        if(!document.contains('/')) return null
        val parentId=document.substringBeforeLast('/')
        val grants=context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }
        for(grant in grants) try {
            val tree=DocumentsContract.getTreeDocumentId(grant.uri)
            if(grant.uri.authority==uri.authority && (parentId==tree || parentId.startsWith(tree+"/"))) {
                return Storage.resolve(context,DocumentsContract.buildDocumentUriUsingTree(grant.uri,parentId).toString())
            }
        } catch(_:Exception) {}
        return null
    }
    data class Folder(val label:String,val node:Node,val videos:List<QueueEntry>)
    data class Scan(val direct:List<QueueEntry>,val folders:List<Folder>)
    fun scan(folder:Node,cancel:()->Boolean={false}):Scan {
        val visited=hashSetOf<String>(); val folders=mutableListOf<Folder>(); var count=0
        fun walk(node:Node,label:String,depth:Int):List<QueueEntry> {
            check(!cancel()) { "폴더 검색 취소됨" }; require(depth<=32) { "하위 폴더가 너무 깊습니다" }
            if(!visited.add(node.id)) return emptyList()
            val children=node.children().sortedBy { it.name.lowercase(java.util.Locale.ROOT) }
            val direct=children.filter { Storage.video(it) }.map { count++; require(count<=MAX_ENTRIES) { "영상이 10,000개를 초과합니다. 더 작은 폴더를 선택하세요" }; QueueEntry(it.id,it.name,it.size,it.modified) }
            val nested=children.filter { it.directory }.flatMap { child -> val path=if(label.isEmpty()) child.name else "$label / ${child.name}"; val found=walk(child,path,depth+1); if(found.isNotEmpty()) folders.add(Folder(path,child,found)); found }
            return direct+nested
        }
        val all=walk(folder,"",0)
        val nestedIds=folders.flatMap { it.videos }.map { it.id }.toSet()
        return Scan(all.filter { it.id !in nestedIds },folders.sortedBy { it.label.lowercase(java.util.Locale.ROOT) })
    }
}

/** Named playlists are user-visible JSON documents in a user-selected folder. */
class PlaylistFiles(private val activity:Activity,private val launch:(List<QueueEntry>,Int,String)->Unit) {
    companion object { const val CHOOSE_FOLDER=41; const val FIND_LIST=42 }
    var lastError=""
        private set
    private val prefs=activity.getSharedPreferences("playlistFiles",0)
    private var pendingStateFile="playlist-pending-${java.util.UUID.randomUUID()}.json"
    private var pendingName=""
    private var pendingItems=emptyList<QueueEntry>()
    private fun error(e:Throwable)=AlertDialog.Builder(activity).setTitle("재생목록 확인").setMessage(e.message ?: "작업 실패").setPositiveButton("닫기",null).show()
    private fun work(job:()->Unit) { val progress=ProgressDialog.show(activity,"재생목록","처리 중…",true,false); Thread { try { job() } catch(e:Exception) { lastError=android.util.Log.getStackTraceString(e); android.util.Log.e("PlaylistFiles","Playlist operation failed",e); activity.runOnUiThread { if(!activity.isFinishing) error(e) } } finally { activity.runOnUiThread { progress.dismiss() } } }.start() }
    private fun recent():List<Pair<String,String>> { val a=JSONArray(prefs.getString("recent","[]")); return (0 until a.length()).map { a.getJSONObject(it).let { o->o.getString("id") to o.getString("name") } }.distinctBy { it.first }.take(5) }
    private fun remember(id:String,name:String) { val a=JSONArray(); (listOf(id to name)+recent()).distinctBy { it.first }.take(5).forEach { a.put(JSONObject().put("id",it.first).put("name",it.second)) }; prefs.edit().putString("recent",a.toString()).apply() }
    private fun read(node:Node):List<QueueEntry> { val bytes=node.input().use { it.readBytesLimited() }; return Playlists.decode(String(bytes,Charsets.UTF_8)) }
    private fun java.io.InputStream.readBytesLimited():ByteArray { val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192); while(true) { val n=read(buffer); if(n<0) break; require(out.size()+n<=8_000_000) { "재생목록 파일은 최대 8MB입니다" }; out.write(buffer,0,n) }; return out.toByteArray() }
    private fun write(node:Node,items:List<QueueEntry>) { require(items.size<=Playlists.MAX_ENTRIES); val text=Playlists.encode(items); require(text.toByteArray(Charsets.UTF_8).size<=8_000_000); node.output().use { it.write(text.toByteArray(Charsets.UTF_8)) }; require(read(node)==items) { "재생목록 저장 검증 실패" } }
    private fun add(id:String,items:List<QueueEntry>)=work {
        val node=Storage.resolve(activity,id); val old=read(node); val combined=QueueModel.unique(old+items)
        val backup=File(activity.filesDir,"playlist-backup-${System.currentTimeMillis()}.json"); backup.writeText(Playlists.encode(old))
        try { write(node,combined); backup.delete() } catch(e:Exception) { try { write(node,old); backup.delete() } catch(_:Exception) {}; throw IllegalStateException("저장 실패. 기존 목록 복구를 시도했습니다. 남은 백업: ${backup.name}",e) }
        remember(id,node.name); activity.runOnUiThread { Toast.makeText(activity,"${node.name}에 ${combined.size-old.size}개 추가",Toast.LENGTH_LONG).show() }
    }
    private fun create(name:String,items:List<QueueEntry>) {
        val folder=prefs.getString("folder",null)
        if(folder==null) { pendingName=name; pendingItems=items; activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION),CHOOSE_FOLDER); return }
        work { val parent=Storage.tree(activity,Uri.parse(folder)); val node=parent.create(name+".tplaylist.json"); try { write(node,QueueModel.unique(items)) } catch(e:Exception) { try { node.delete() } catch(_:Exception) {}; throw e }; remember(node.id,node.name); activity.runOnUiThread { Toast.makeText(activity,"재생목록 생성 완료: ${node.name}",Toast.LENGTH_LONG).show() } }
    }
    fun showAdd(current:QueueEntry,queue:List<QueueEntry>) {
        val form=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; setPadding(24,12,24,12) }
        form.addView(TextView(activity).apply { text="추가할 영상: ${current.name}" })
        val whole=CheckBox(activity).apply { text="현재 재생목록 전체 추가 (${queue.size}개)" }; form.addView(whole)
        fun items()=if(whole.isChecked) queue else listOf(current)
        val line=LinearLayout(activity)
        val name=EditText(activity).apply { hint="새 재생목록 이름"; setSingleLine(true) }; line.addView(name,LinearLayout.LayoutParams(0,-2,1f))
        val dialog=AlertDialog.Builder(activity).setTitle("재생목록에 추가").setView(ScrollView(activity).apply { addView(form) }).setNegativeButton("닫기",null).create()
        line.addView(Button(activity).apply { text="생성"; setOnClickListener { try { val label=name.text.toString().trim(); validateName(label); require(label.length<=100) { "이름은 100자 이하로 입력하세요" }; create(label,items()); dialog.dismiss() } catch(e:Exception) { name.error=e.message } } }); form.addView(line)
        form.addView(TextView(activity).apply { text="최근 추가한 재생목록 (최대 5개)" })
        recent().forEach { (id,label) -> form.addView(Button(activity).apply { text=label; setOnClickListener { add(id,items()); dialog.dismiss() } }) }
        form.addView(Button(activity).apply { text="추가할 재생목록 찾기"; setOnClickListener { pendingItems=items(); find(true); dialog.dismiss() } })
        form.addView(Button(activity).apply { text="저장 폴더 변경"; setOnClickListener { pendingName=""; pendingItems=emptyList(); chooseFolder(); dialog.dismiss() } })
        dialog.show()
    }
    private fun chooseFolder() { val intent=Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION); initial(intent); activity.startActivityForResult(intent,CHOOSE_FOLDER) }
    private fun initial(intent:Intent) { prefs.getString("folder",null)?.let { tree -> try { intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,DocumentsContract.buildDocumentUriUsingTree(Uri.parse(tree),DocumentsContract.getTreeDocumentId(Uri.parse(tree)))) } catch(_:Exception) {} } }
    fun find(adding:Boolean=false) { if(!adding) pendingItems=emptyList(); val intent=Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type="*/*"; addCategory(Intent.CATEGORY_OPENABLE); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) }; initial(intent); activity.startActivityForResult(intent,FIND_LIST) }
    fun result(request:Int,result:Int,data:Intent?):Boolean {
        if(request !in listOf(CHOOSE_FOLDER,FIND_LIST)) return false
        val uri=data?.data
        if(result!=Activity.RESULT_OK || uri==null) { pendingName=""; pendingItems=emptyList(); return true }
        try {
            val grant=data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            try { activity.contentResolver.takePersistableUriPermission(uri,if(grant and Intent.FLAG_GRANT_WRITE_URI_PERMISSION!=0) Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION else Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch(_:Exception) {}
            if(request==CHOOSE_FOLDER) { prefs.edit().putString("folder",uri.toString()).apply(); val name=pendingName; val items=pendingItems; pendingName=""; pendingItems=emptyList(); if(name.isNotEmpty()) create(name,items) }
            else { val items=pendingItems; pendingItems=emptyList(); if(items.isNotEmpty()) add(uri.toString(),items) else work { val node=Storage.resolve(activity,uri.toString()); val list=read(node); require(list.isNotEmpty()) { "재생목록이 비어 있습니다" }; activity.runOnUiThread { launch(list,0,node.name) } } }
        } catch(e:Exception) { error(e) }; return true
    }
    fun saveState(out:android.os.Bundle) { out.putString("pendingPlaylistName",pendingName); File(activity.cacheDir,pendingStateFile).writeText(Playlists.encode(pendingItems)); out.putString("pendingPlaylistFile",pendingStateFile) }
    fun restore(state:android.os.Bundle?) { pendingName=state?.getString("pendingPlaylistName") ?: ""; pendingItems=state?.getString("pendingPlaylistFile")?.takeIf { it.matches(Regex("playlist-pending-[a-f0-9-]+\\.json")) }?.let { pendingStateFile=it; val file=File(activity.cacheDir,it); if(file.exists()) Playlists.decode(file.readText()) else emptyList() } ?: emptyList() }
}
