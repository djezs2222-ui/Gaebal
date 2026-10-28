package kr.personal.tabletplayer
import org.junit.Assert.*
import org.junit.Test
import java.io.*
class QueueModelTest {
    private val items=listOf(QueueEntry("b","b.mp4",30,2),QueueEntry("a","a.mp4",20,3),QueueEntry("c","c.mp4",10,1))
    @Test fun nameBothDirections() { assertEquals(listOf("a","b","c"),QueueModel.sorted(items,0,false).map { it.id }); assertEquals(listOf("c","b","a"),QueueModel.sorted(items,0,true).map { it.id }) }
    @Test fun sizeAndDate() { assertEquals(listOf("c","a","b"),QueueModel.sorted(items,1,false).map { it.id }); assertEquals(listOf("a","b","c"),QueueModel.sorted(items,2,true).map { it.id }) }
    @Test fun sortKeepsPlayingIdentityAndInput() { val id=items[0].id; val sorted=QueueModel.sorted(items,1,false); assertEquals(2,sorted.indexOfFirst { it.id==id }); assertEquals("b",items[0].id) }
    @Test fun duplicateFoldersDoNotDuplicateVideos() { assertEquals(items,QueueModel.unique(items+items)) }
    private class N(override val name:String,override val directory:Boolean=false):Node {
        override val id=name; override val size=12L; override val modified=1L; val entries=mutableListOf<N>()
        override fun children():List<Node> = entries
        override fun writable()=false; override fun available():Long?=null
        override fun input():InputStream=error("unused"); override fun output():OutputStream=error("unused")
        override fun create(name:String,directory:Boolean):Node=error("unused"); override fun rename(name:String):Node=error("unused"); override fun delete()=false
    }
    @Test fun folderScanSeparatesDirectAndNestedVideos() {
        val root=N("root",true); val sub=N("sub",true); val deep=N("deep",true); val empty=N("empty",true)
        root.entries.addAll(listOf(N("b.mp4"),N("a.MKV"),N("readme.txt"),sub,empty)); sub.entries.add(deep); deep.entries.add(N("c.mp4"))
        val scan=Playlists.scan(root); assertEquals(listOf("a.MKV","b.mp4"),scan.direct.map { it.name }); assertEquals(listOf("sub","sub / deep"),scan.folders.map { it.label }); assertEquals(1,QueueModel.unique(scan.folders.flatMap { it.videos }).size)
    }
    @Test(expected=IllegalStateException::class) fun scanCancellation() { Playlists.scan(N("root",true)) { true } }
}
