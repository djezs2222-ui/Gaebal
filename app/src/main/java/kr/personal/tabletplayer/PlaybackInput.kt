package kr.personal.tabletplayer

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException

/** Keeps the provider-owned descriptor alive until VLC has stopped reading it. */
class PlaybackInput private constructor(private val uri:Uri,private val asset:AssetFileDescriptor?):Closeable {
    val mode:String get()=if(asset!=null) "Android 문서 파일 핸들" else "${uri.scheme ?: "unknown"} 스트림"
    fun media(vlc:LibVLC):Media = if(asset!=null) Media(vlc,asset) else Media(vlc,uri)
    override fun close() { asset?.close() }
    companion object {
        fun open(context:Context,uri:Uri):PlaybackInput {
            if(uri.scheme=="content") {
                val asset=context.contentResolver.openAssetFileDescriptor(uri,"r") ?: throw FileNotFoundException("문서 제공자가 영상 파일을 열지 못했습니다")
                if(!asset.fileDescriptor.valid()) { asset.close(); throw FileNotFoundException("영상 파일 핸들이 유효하지 않습니다") }
                return PlaybackInput(uri,asset)
            }
            if(uri.scheme=="file") {
                val file=File(uri.path ?: throw FileNotFoundException("파일 경로 없음"))
                if(!file.isFile) throw FileNotFoundException("선택한 영상 파일을 찾을 수 없습니다")
                if(!file.canRead()) throw SecurityException("선택한 영상을 읽을 권한이 없습니다")
            }
            return PlaybackInput(uri,null)
        }
    }
}
