package kr.personal.tabletplayer

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/** Debug-only, unexported fixture provider. Never serves user-selected files. */
class TestMediaProvider:ContentProvider() {
    override fun onCreate()=true
    private fun validate(uri:Uri,mode:String) {
        if(mode!="r" || uri.lastPathSegment=="denied") throw SecurityException("테스트 문서 읽기 권한 거부")
        require(uri.lastPathSegment in listOf("영상 한글.mp4","offset.mp4"))
    }
    override fun getType(uri:Uri)="video/mp4"
    override fun query(uri:Uri,projection:Array<out String>?,selection:String?,args:Array<out String>?,sort:String?):Cursor {
        val columns=projection ?: arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE)
        return MatrixCursor(columns).apply { addRow(columns.map { if(it==OpenableColumns.DISPLAY_NAME) "영상 한글.mp4" else if(it==OpenableColumns.SIZE) File(context!!.filesDir,"smoke.mp4").length() else null }) }
    }
    override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor { validate(uri,mode); return ParcelFileDescriptor.open(File(context!!.filesDir,"smoke.mp4"),ParcelFileDescriptor.MODE_READ_ONLY) }
    override fun openAssetFile(uri:Uri,mode:String):AssetFileDescriptor {
        validate(uri,mode)
        if(uri.lastPathSegment=="offset.mp4") {
            val clip=File(context!!.filesDir,"smoke.mp4")
            val wrapped=File(context!!.cacheDir,"smoke-offset.bin")
            wrapped.outputStream().use { out -> out.write(ByteArray(4096) { 0x5a }); clip.inputStream().use { it.copyTo(out) }; out.write(ByteArray(4096) { 0x33 }) }
            return AssetFileDescriptor(ParcelFileDescriptor.open(wrapped,ParcelFileDescriptor.MODE_READ_ONLY),4096,clip.length())
        }
        return AssetFileDescriptor(openFile(uri,mode),0,AssetFileDescriptor.UNKNOWN_LENGTH)
    }
    override fun insert(uri:Uri,values:ContentValues?):Uri?=throw UnsupportedOperationException("read only")
    override fun update(uri:Uri,values:ContentValues?,selection:String?,args:Array<out String>?):Int=throw UnsupportedOperationException("read only")
    override fun delete(uri:Uri,selection:String?,args:Array<out String>?):Int=throw UnsupportedOperationException("read only")
}
