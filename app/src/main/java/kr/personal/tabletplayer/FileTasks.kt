package kr.personal.tabletplayer

import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

data class Snapshot(val node: Node,val name: String=node.name,val size: Long=node.size,val modified: Long=node.modified) {
    fun unchanged() = node.size==size && node.modified==modified && node.name==name && !node.directory
}
data class SearchFilter(val recursive: Boolean=true,val videosOnly: Boolean=true,val minBytes: Long=500_000_000L,val maxBytes: Long?=null,val after: Long?=null,val before: Long?=null) {
    init { require(minBytes>=0); require(maxBytes==null || maxBytes>=minBytes); require(after==null || before==null || after<=before) }
    fun matches(name: String,size: Long,date: Long): Boolean {
        val ext=name.substringAfterLast('.',"").lowercase()
        return (!videosOnly || ext in setOf("mp4","mkv","avi","mov","webm","m4v","ts","m2ts","mts","wmv","flv","mpg","mpeg","3gp","vob","ogv")) && size>=minBytes && (maxBytes==null || size<=maxBytes) && (after==null || date>=after) && (before==null || date<=before)
    }
}
object FileTasks {
    fun search(root: Node,filter: SearchFilter,cancel: AtomicBoolean,progress: (Int)->Unit): List<Snapshot> {
        val found=mutableListOf<Snapshot>(); val stack=java.util.ArrayDeque<Node>(); stack.add(root); val visited=hashSetOf<String>(); var scanned=0
        while(stack.isNotEmpty()) {
            check(!cancel.get()) { "검색 취소됨" }
            val dir=stack.removeLast(); if(!visited.add(dir.id)) continue
            for(node in dir.children()) {
                check(!cancel.get()) { "검색 취소됨" }; scanned++; if(scanned%50==0) progress(scanned)
                if(node.directory) { if(filter.recursive) stack.add(node) }
                else { val item=Snapshot(node); if(filter.matches(item.name,item.size,item.modified)) found.add(item) }
            }
        }
        progress(scanned); return found
    }
    fun preflight(items: List<Snapshot>,dest: Node,move: Boolean): String {
        require(items.isNotEmpty()) { "파일을 선택하세요" }
        require(dest.directory && dest.writable()) { "목적지 쓰기 권한이 없습니다" }
        require(items.all { it.unchanged() }) { "검색 후 원본이 변경되었습니다. 다시 검색하세요" }
        if(move) require(items.all { it.node.writable() }) { "원본 삭제 권한이 없습니다" }
        require(items.map { it.name.lowercase() }.distinct().size==items.size) { "선택 파일의 이름이 서로 같습니다. 나눠서 작업하세요" }
        val existing=dest.children().map { it.name.lowercase() }.toSet()
        require(items.none { it.name.lowercase() in existing }) { "목적지에 같은 이름이 있습니다. 덮어쓰지 않습니다" }
        require(items.none { it.node.id==dest.id })
        val total=items.fold(0L) { sum,item -> Math.addExact(sum,item.size) }
        val free=dest.available() ?: error("이 저장소가 남은 용량을 제공하지 않아 안전하게 실행할 수 없습니다")
        require(free>=Math.addExact(total,16_000_000L)) { "저장 공간이 부족합니다 (16MB 여유 공간 필요)" }
        return "${items.size}개 · ${total/1_000_000}MB\n남은 용량 ${free/1_000_000}MB\n중복 없음 · 권한 확인\n복사 후 SHA-256 재검증${if(move) " 후 원본 삭제" else ""}"
    }
    fun transfer(items: List<Snapshot>,dest: Node,move: Boolean,cancel: AtomicBoolean,log: (String)->Unit) {
        preflight(items,dest,move)
        for(item in items) {
            if(cancel.get()) { log("취소: 나머지 원본 보존"); break }
            var temporary: Node?=null; var committed=false
            try {
                require(item.unchanged()) { "원본 변경 감지" }
                log("시작: ${item.name}")
                val tempName=".tabletplayer-${java.util.UUID.randomUUID()}.partial"
                temporary=dest.create(tempName)
                val digest=MessageDigest.getInstance("SHA-256"); var bytes=0L
                temporary.output().use { output -> item.node.input().use { input ->
                    val buffer=ByteArray(256*1024)
                    while(true) { check(!cancel.get()) { "취소됨" }; val n=input.read(buffer); if(n<0) break; output.write(buffer,0,n); digest.update(buffer,0,n); bytes+=n }
                    output.flush()
                } }
                require(bytes==item.size && item.unchanged()) { "복사 도중 원본 변경 또는 크기 불일치" }
                val hash=digest.digest()
                require(hash.contentEquals(hash(temporary,cancel))) { "목적지 내용 검증 실패" }
                check(!cancel.get()) { "취소됨" }
                require(dest.children().none { it.name.equals(item.name,true) }) { "실행 중 중복 이름 발생" }
                val completed=temporary.rename(item.name)
                temporary=completed
                require(completed.name==item.name && completed.size==item.size && hash.contentEquals(hash(completed,cancel))) { "최종 이름 또는 파일 검증 실패" }
                committed=true
                log("검증된 복사 완료: ${item.name}")
                if(move) {
                    // Re-read source before deleting; changed source is retained even when destination is valid.
                    require(item.unchanged() && hash.contentEquals(hash(item.node,cancel))) { "원본 변경 감지: 원본 보존" }
                    check(!cancel.get()) { "취소: 검증된 복사와 원본 모두 보존" }
                    require(item.node.delete()) { "원본 삭제 실패: 복사와 원본 모두 보존" }
                    log("이동 완료: ${item.name}")
                }
            } catch(e: Exception) {
                if(!committed) try { temporary?.delete() } catch(_:Exception) {}
                log("중단: ${item.name} · ${e.message} · 원본 보존")
                break // Stop the batch; never retry a destructive step automatically.
            }
        }
    }
    fun hash(node: Node,cancel: AtomicBoolean): ByteArray {
        val md=MessageDigest.getInstance("SHA-256")
        node.input().use { input -> val buf=ByteArray(256*1024); while(true) { check(!cancel.get()) { "취소됨" }; val n=input.read(buf); if(n<0) break; md.update(buf,0,n) } }
        return md.digest()
    }
}
