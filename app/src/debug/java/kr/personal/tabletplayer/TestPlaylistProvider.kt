package kr.personal.tabletplayer
import android.provider.DocumentsProvider
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.database.Cursor
import android.database.MatrixCursor
import android.os.*
import java.io.File
/** Private debug fixture: only a dedicated app-private folder is reachable. */
class TestPlaylistProvider:DocumentsProvider() {
    override fun onCreate()=true
    private fun root()=File(context!!.filesDir,"playlist-fixtures").apply { mkdirs() }
    private fun file(id:String):File { require(id=="lists" || (id.startsWith("lists/") && !id.removePrefix("lists/").contains('/') && !id.contains(".."))); return if(id=="lists") root() else File(root(),id.removePrefix("lists/")) }
    private val columns=arrayOf(Document.COLUMN_DOCUMENT_ID,Document.COLUMN_DISPLAY_NAME,Document.COLUMN_MIME_TYPE,Document.COLUMN_FLAGS,Document.COLUMN_SIZE,Document.COLUMN_LAST_MODIFIED)
    private fun row(cursor:MatrixCursor,id:String) { val f=file(id); cursor.addRow(cursor.columnNames.map { when(it) { Document.COLUMN_DOCUMENT_ID->id; Document.COLUMN_DISPLAY_NAME->f.name; Document.COLUMN_MIME_TYPE->if(f.isDirectory) Document.MIME_TYPE_DIR else "application/json"; Document.COLUMN_FLAGS->if(f.isDirectory) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE; Document.COLUMN_SIZE->f.length(); Document.COLUMN_LAST_MODIFIED->f.lastModified(); else->null } }) }
    override fun queryRoots(projection:Array<out String>?):Cursor=MatrixCursor(projection ?: arrayOf(Root.COLUMN_ROOT_ID,Root.COLUMN_DOCUMENT_ID,Root.COLUMN_TITLE,Root.COLUMN_FLAGS)).apply { addRow(columnNames.map { when(it) { Root.COLUMN_ROOT_ID->"fixture"; Root.COLUMN_DOCUMENT_ID->"lists"; Root.COLUMN_TITLE->"Test lists"; Root.COLUMN_FLAGS->Root.FLAG_SUPPORTS_CREATE; else->null } }) }
    override fun queryDocument(id:String,projection:Array<out String>?):Cursor=MatrixCursor(projection ?: columns).also { require(file(id).exists()); row(it,id) }
    override fun queryChildDocuments(parent:String,projection:Array<out String>?,sort:String?):Cursor=MatrixCursor(projection ?: columns).also { cursor -> require(parent=="lists"); root().listFiles().orEmpty().forEach { row(cursor,"lists/"+it.name) } }
    override fun createDocument(parent:String,mime:String,name:String):String { require(parent=="lists"); validateName(name); val id="lists/$name"; require(file(id).createNewFile()); return id }
    override fun openDocument(id:String,mode:String,signal:CancellationSignal?):ParcelFileDescriptor=ParcelFileDescriptor.open(file(id),ParcelFileDescriptor.parseMode(mode))
    override fun deleteDocument(id:String) { require(id!="lists"); require(file(id).delete()) }
}
