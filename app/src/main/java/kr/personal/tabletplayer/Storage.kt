package kr.personal.tabletplayer

import android.content.Context
import android.net.Uri
import android.os.storage.StorageManager
import android.provider.DocumentsContract

import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import java.io.*
import java.util.Properties

interface Node {
    val id: String
    val name: String
    val directory: Boolean
    val size: Long
    val modified: Long
    fun children(): List<Node>
    fun writable(): Boolean
    fun input(): InputStream
    fun output(): OutputStream
    fun create(name: String, directory: Boolean=false): Node
    fun rename(name: String): Node
    fun delete(): Boolean
    fun available(): Long? // null means provider does not expose capacity; execution is blocked.
}
object Storage {
    private val sessions = java.util.concurrent.ConcurrentHashMap<String,CIFSContext>()
    fun connect(host: String, share: String, domain: String, user: String, password: String): Node {
        require(host.matches(Regex("[a-zA-Z0-9.:-]+"))) { "서버 주소에는 IP 또는 호스트 이름만 입력하세요" }
        require(share.isNotBlank() && !share.contains('/') && !share.contains('\\')) { "공유 폴더 이름을 확인하세요" }
        val props=Properties().apply { setProperty("jcifs.smb.client.minVersion","SMB202"); setProperty("jcifs.smb.client.maxVersion","SMB311"); setProperty("jcifs.smb.client.responseTimeout","15000"); setProperty("jcifs.smb.client.connTimeout","10000") }
        val context=BaseContext(PropertyConfiguration(props)).withCredentials(NtlmPasswordAuthenticator(domain,user,password))
        val root="smb://$host/${Uri.encode(share)}/"
        sessions[root]=context
        return SmbNode(root,context).also { require(it.directory) { "공유 폴더를 열 수 없습니다" }; it.children() }
    }
    fun shares(host:String,domain:String,user:String,password:String):List<String> {
        require(host.matches(Regex("[a-zA-Z0-9.:-]+"))) { "서버 주소에는 IP 또는 호스트 이름만 입력하세요" }
        val props=Properties().apply { setProperty("jcifs.smb.client.minVersion","SMB202"); setProperty("jcifs.smb.client.maxVersion","SMB311"); setProperty("jcifs.smb.client.responseTimeout","5000"); setProperty("jcifs.smb.client.connTimeout","3000") }
        val context=BaseContext(PropertyConfiguration(props)).withCredentials(NtlmPasswordAuthenticator(domain,user,password))
        return try { SmbFile("smb://$host/",context).use { server -> server.listFiles().mapNotNull { child -> child.use { val name=it.name.trimEnd('/'); name.takeIf { name.isNotBlank() && !name.equals("IPC$",true) && it.isDirectory } } }.distinct().sorted() } }
        finally { try { context.close() } catch(_:Exception) {} }
    }
    fun resolve(context: Context,id: String): Node {
        if(id.startsWith("smb://")) {
            val key=sessions.keys.firstOrNull { id.startsWith(it) } ?: error("NAS에 다시 로그인하세요")
            return SmbNode(id,sessions.getValue(key))
        }
        val uri=Uri.parse(id)
        return SafNode(context,uri)
    }
    fun tree(context: Context, uri: Uri): Node = SafNode(context,DocumentsContract.buildDocumentUriUsingTree(uri,DocumentsContract.getTreeDocumentId(uri)))
    fun video(node: Node)=!node.directory && node.name.substringAfterLast('.',"").lowercase() in setOf("mp4","mkv","avi","mov","webm","m4v","ts","m2ts","mts","wmv","flv","mpg","mpeg","3gp","vob","ogv")
}
class SafNode(private val context: Context,private var uri: Uri): Node {
    private fun <T> column(key:String,read:(android.database.Cursor)->T):T {
        val cursor=context.contentResolver.query(uri,arrayOf(key),null,null,null) ?: error("저장소 응답 없음")
        return cursor.use { require(it.moveToFirst()) { "파일이 사라졌거나 권한이 없습니다" }; read(it) }
    }
    override val id get()=uri.toString()
    override val name get()=column(DocumentsContract.Document.COLUMN_DISPLAY_NAME) { it.getString(0) ?: "이름 없음" }
    override val directory get()=column(DocumentsContract.Document.COLUMN_MIME_TYPE) { it.getString(0)==DocumentsContract.Document.MIME_TYPE_DIR }
    override val size get()=column(DocumentsContract.Document.COLUMN_SIZE) { require(!it.isNull(0)) { "파일 크기를 알 수 없습니다" }; it.getLong(0) }
    override val modified get()=column(DocumentsContract.Document.COLUMN_LAST_MODIFIED) { if(it.isNull(0)) 0L else it.getLong(0) }
    override fun children():List<Node> {
        val children=DocumentsContract.buildChildDocumentsUriUsingTree(uri,DocumentsContract.getDocumentId(uri))
        val cursor=context.contentResolver.query(children,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),null,null,null) ?: error("폴더 목록 읽기 실패")
        return cursor.use { val list=mutableListOf<Node>(); while(it.moveToNext()) list.add(SafNode(context,DocumentsContract.buildDocumentUriUsingTree(uri,it.getString(0)))); list }
    }
    override fun writable():Boolean {
        val flags=column(DocumentsContract.Document.COLUMN_FLAGS) { it.getInt(0) }
        val needed=if(directory) DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE else DocumentsContract.Document.FLAG_SUPPORTS_DELETE
        return flags and needed != 0 && context.checkUriPermission(uri,android.os.Process.myPid(),android.os.Process.myUid(),android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)==android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    override fun input()=context.contentResolver.openInputStream(uri) ?: error("읽기 실패")
    override fun output()=context.contentResolver.openOutputStream(uri,"wt") ?: error("쓰기 실패")
    override fun create(name:String,directory:Boolean):Node {
        validateName(name); require(children().none { it.name.equals(name,true) }) { "같은 이름이 있습니다: $name" }
        val made=DocumentsContract.createDocument(context.contentResolver,uri,if(directory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream",name) ?: error("파일 생성 실패")
        return SafNode(context,made)
    }
    override fun rename(name:String):Node { validateName(name); uri=DocumentsContract.renameDocument(context.contentResolver,uri,name) ?: error("이름 변경 실패"); return this }
    override fun delete()=DocumentsContract.deleteDocument(context.contentResolver,uri)
    override fun available():Long? {
        return try {
            val key=DocumentsContract.getDocumentId(uri).substringBefore(':')
            val manager=context.getSystemService(StorageManager::class.java)
            fun matches(volume:android.os.storage.StorageVolume)=(key=="primary" && volume.isPrimary) || volume.uuid?.equals(key,true)==true
            val volume=manager.storageVolumes.firstOrNull { matches(it) }
            val direct=if(android.os.Build.VERSION.SDK_INT>=30) volume?.directory?.usableSpace?.takeIf { it>0 } else null
            direct ?: context.getExternalFilesDirs(null).filterNotNull().firstOrNull { file -> manager.getStorageVolume(file)?.let { matches(it) }==true }?.usableSpace?.takeIf { it>0 }
        } catch(_:Exception) { null }
    }
}
class SmbNode(override val id: String,private val context: CIFSContext): Node {
    private fun file()=SmbFile(id,context)
    override val name get()=file().use { it.name.trimEnd('/') }
    override val directory get()=file().use { it.isDirectory }
    override val size get()=file().use { it.length() }
    override val modified get()=file().use { it.lastModified() }
    override fun children(): List<Node> = file().use { parent -> parent.listFiles().map { child -> child.use { SmbNode(it.canonicalPath,context) } } }
    override fun writable()=file().use { it.canWrite() }
    override fun input(): InputStream = file().inputStream
    override fun output(): OutputStream = file().outputStream
    override fun create(name: String,directory: Boolean): Node {
        validateName(name)
        val url=id.trimEnd('/')+"/"+Uri.encode(name)+(if(directory) "/" else "")
        val child=SmbNode(url,context)
        child.file().use { f -> require(!f.exists()) { "같은 이름이 있습니다" }; if(directory) f.mkdir() else f.createNewFile() }
        return child
    }
    override fun rename(name: String): Node {
        validateName(name)
        val target=SmbNode(id.trimEnd('/').substringBeforeLast('/')+"/"+Uri.encode(name)+(if(directory) "/" else ""),context)
        file().use { source -> target.file().use { destination -> require(!destination.exists()); source.renameTo(destination) } }
        return target
    }
    override fun delete(): Boolean { file().use { it.delete() }; return true }
    override fun available(): Long? = file().use { it.diskFreeSpace }.takeIf { it>=0 }
}
fun validateName(name: String) { require(name.isNotBlank() && name!="." && name!=".." && name.none { it in "/\\\u0000" }) { "사용할 수 없는 이름입니다" } }


