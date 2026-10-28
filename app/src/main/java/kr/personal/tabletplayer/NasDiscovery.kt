package kr.personal.tabletplayer

import android.content.Context
import android.net.*
import android.net.nsd.*
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/** A user-initiated Wi-Fi-only SMB discovery session. Never logs in or scans mobile/VPN routes. */
class NasDiscovery(context:Context,private val found:(String,String)->Unit,private val status:(String)->Unit):AutoCloseable {
    private val connectivity=context.getSystemService(ConnectivityManager::class.java)
    private val nsd=context.getSystemService(NsdManager::class.java)
    private val handler=Handler(Looper.getMainLooper())
    private val closed=AtomicBoolean(false)
    private val pool=Executors.newFixedThreadPool(24)
    private val sockets=ConcurrentHashMap.newKeySet<Socket>()
    private var listener:NsdManager.DiscoveryListener?=null
    private val pending=ArrayDeque<NsdServiceInfo>()
    private var resolving=false
    private var network:Network?=null
    private var address:ByteArray?=null
    private var prefix=0
    private val results=hashSetOf<String>()
    fun start() {
        val wifi=connectivity.allNetworks.firstOrNull { connectivity.getNetworkCapabilities(it)?.let { caps -> caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }==true }
        val link=wifi?.let { connectivity.getLinkProperties(it) }?.linkAddresses?.firstOrNull { it.address is Inet4Address && Ipv4Plan.local(it.address.address) }
        if(wifi==null || link==null) { status("연결된 Wi-Fi의 로컬 IPv4 주소가 없습니다. Wi-Fi를 연결하거나 서버 주소를 직접 입력하세요."); close(); return }
        network=wifi; address=link.address.address; prefix=link.prefixLength
        status("현재 Wi-Fi에서 SMB 서버 검색 중… (최대 20초)")
        discoverAdvertisements()
        val hosts=Ipv4Plan.hosts(link.address.address,link.prefixLength)
        Thread {
            try {
                val completion=ExecutorCompletionService<Unit>(pool)
                var submitted=0
                hosts.forEach { host -> if(!closed.get()) { completion.submit(Callable { probe(host,host); Unit }); submitted++ } }
                var completed=0
                while(completed<submitted && !closed.get()) {
                    val task=completion.poll(500,TimeUnit.MILLISECONDS) ?: continue
                    try { task.get() } catch(_:Exception) {}; completed++
                }
                handler.post { if(!closed.get()) status("주소 검색 완료 · 자동 광고 확인 중… 발견 ${results.size}개") }
            } catch(_:Exception) { /* cancellation/disconnect: the bounded session still expires */ }
        }.start()
        handler.postDelayed({ if(!closed.get()) { status(if(results.isEmpty()) "SMB 서버를 찾지 못했습니다. Wi-Fi 격리·SMB 사용 여부를 확인하거나 주소를 직접 입력하세요." else "검색 완료 · SMB 서버 ${results.size}개 · 아래에서 선택하세요"); close() } },20000)
    }
    private fun probe(host:String,name:String) {
        if(closed.get()) return
        val wifi=network ?: return
        val caps=connectivity.getNetworkCapabilities(wifi) ?: return
        if(!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
        var socket:Socket?=null
        try {
            socket=wifi.socketFactory.createSocket(); sockets.add(socket)
            if(closed.get()) return
            socket.connect(InetSocketAddress(host,445),350)
            handler.post { if(!closed.get()) { results.add(host); found(host,name); status("검색 중… SMB 서버 ${results.size}개 발견 · 선택 후 공유 폴더를 확인하세요") } }
        } catch(_:Exception) { } finally { socket?.let { sockets.remove(it); try { it.close() } catch(_:Exception) {} } }
    }
    private fun discoverAdvertisements() {
        val discovery=object:NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type:String) {}
            override fun onDiscoveryStopped(type:String) {}
            override fun onStartDiscoveryFailed(type:String,error:Int) { /* unicast probing remains available */ }
            override fun onStopDiscoveryFailed(type:String,error:Int) {}
            override fun onServiceLost(info:NsdServiceInfo) {}
            override fun onServiceFound(info:NsdServiceInfo) { handler.post { if(!closed.get()) { pending.addLast(info); resolveNext() } } }
        }
        listener=discovery
        try { nsd.discoverServices("_smb._tcp.",NsdManager.PROTOCOL_DNS_SD,discovery) } catch(_:Exception) { listener=null }
    }
    @Suppress("DEPRECATION") private fun resolveNext() {
        if(closed.get() || resolving || pending.isEmpty()) return
        resolving=true
        val info=pending.removeFirst()
        try { nsd.resolveService(info,object:NsdManager.ResolveListener {
            override fun onResolveFailed(service:NsdServiceInfo,error:Int) { handler.post { resolving=false; resolveNext() } }
            override fun onServiceResolved(service:NsdServiceInfo) { handler.post {
                if(!closed.get()) {
                    val ip=service.host
                    if(ip is Inet4Address && address?.let { Ipv4Plan.contains(it,prefix,ip.address) }==true) try { pool.submit { probe(ip.hostAddress!!,service.serviceName) } } catch(_:RejectedExecutionException) {}
                    resolving=false; resolveNext()
                }
            } }
        }) } catch(_:Exception) { resolving=false; resolveNext() }
    }
    override fun close() {
        if(!closed.compareAndSet(false,true)) return
        handler.removeCallbacksAndMessages(null); pending.clear()
        listener?.let { try { nsd.stopServiceDiscovery(it) } catch(_:Exception) {} }; listener=null
        sockets.forEach { try { it.close() } catch(_:Exception) {} }; sockets.clear(); pool.shutdownNow()
    }
}
