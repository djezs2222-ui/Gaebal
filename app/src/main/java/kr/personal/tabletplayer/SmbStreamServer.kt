package kr.personal.tabletplayer

import android.net.Uri
import java.net.*
import java.io.*
import java.util.UUID
import java.util.concurrent.Executors

/** Private loopback HTTP range bridge: NAS passwords never enter VLC URLs or logs. */
class SmbStreamServer(private val node: Node): Closeable {
    private val server=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
    private val token=UUID.randomUUID().toString()
    private val workers=Executors.newFixedThreadPool(3)
    private val sockets=java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    @Volatile private var closed=false
    val uri: Uri=Uri.parse("http://127.0.0.1:${server.localPort}/$token")
    init { Thread { while(!closed) { try { val socket=server.accept(); sockets.add(socket); workers.submit { serve(socket) } } catch(_:Exception) { if(!closed) close() } } }.start() }
    private fun serve(socket: Socket) {
        try { socket.use { client ->
            client.soTimeout=15000
            val reader=client.getInputStream().bufferedReader(Charsets.US_ASCII)
            val request=reader.readLine()?.split(' ') ?: return
            val headers=mutableMapOf<String,String>()
            var count=0
            while(true) { val line=reader.readLine() ?: return; if(line.isEmpty()) break; require(++count<100 && line.length<8192); headers[line.substringBefore(':').lowercase()]=line.substringAfter(':').trim() }
            val out=client.getOutputStream()
            if(request.size<2 || request[1]!="/$token" || request[0] !in listOf("GET","HEAD")) { out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return }
            val total=node.size
            val range=headers["range"]
            var start=0L; var end=total-1
            if(range!=null) {
                val match=Regex("bytes=(\\d*)-(\\d*)").matchEntire(range)
                if(match==null || total<=0) { invalidRange(out,total); return }
                val a=match.groupValues[1]; val b=match.groupValues[2]
                if(a.isEmpty()) { val suffix=b.toLongOrNull() ?: 0; if(suffix<=0) { invalidRange(out,total); return }; start=(total-suffix).coerceAtLeast(0) }
                else { start=a.toLongOrNull() ?: total; if(b.isNotEmpty()) end=(b.toLongOrNull() ?: -1).coerceAtMost(total-1) }
                if(start<0 || start>=total || end<start) { invalidRange(out,total); return }
            }
            val length=if(total==0L) 0 else end-start+1
            val status=if(range==null) "200 OK" else "206 Partial Content"
            val extra=if(range==null) "" else "Content-Range: bytes $start-$end/$total\r\n"
            out.write("HTTP/1.1 $status\r\nContent-Type: application/octet-stream\r\nAccept-Ranges: bytes\r\nContent-Length: $length\r\n${extra}Connection: close\r\n\r\n".toByteArray())
            if(request[0]=="HEAD") return
            node.input().use { input ->
                var skip=start
                while(skip>0) { val n=input.skip(skip); if(n>0) skip-=n else { if(input.read()<0) throw EOFException(); skip-- } }
                val buffer=ByteArray(256*1024); var remaining=length
                while(remaining>0 && !closed) { val n=input.read(buffer,0,minOf(buffer.size.toLong(),remaining).toInt()); if(n<0) throw EOFException(); out.write(buffer,0,n); remaining-=n }
            }
        } } catch(_:Exception) { /* VLC reconnects or reports a playback error. */ } finally { sockets.remove(socket); try { socket.close() } catch(_:Exception) {} }
    }
    private fun invalidRange(out: OutputStream,total: Long) { out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$total\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()) }
    override fun close() { closed=true; try { server.close() } catch(_:Exception) {}; sockets.forEach { try { it.close() } catch(_:Exception) {} }; workers.shutdownNow() }
}
