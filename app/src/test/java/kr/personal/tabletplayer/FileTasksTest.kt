package kr.personal.tabletplayer
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.concurrent.atomic.AtomicBoolean

class FileTasksTest {
    private class MemoryNode(override var name:String,private var content:ByteArray=byteArrayOf(),override val directory:Boolean=false):Node {
        override val id="mem:"+java.util.UUID.randomUUID()
        var timestamp=1L; var free=1_000_000_000L; var write=true; var removed=false; var corrupt=false
        val entries=mutableListOf<MemoryNode>()
        override val size get()=content.size.toLong()
        override val modified get()=timestamp
        override fun children():List<Node> = entries.filter { !it.removed }
        override fun writable()=write
        override fun input():InputStream=ByteArrayInputStream(content)
        override fun output():OutputStream=object:ByteArrayOutputStream() { override fun close() { super.close(); content=toByteArray(); if(corrupt && content.isNotEmpty()) content[0]=(content[0]+1).toByte() } }
        override fun create(name:String,directory:Boolean):Node=MemoryNode(name,directory=directory).also { it.corrupt=corrupt; entries.add(it) }
        override fun rename(name:String):Node { this.name=name; return this }
        override fun delete():Boolean { removed=true; return true }
        override fun available():Long?=free.takeIf { it>=0 }
    }
    private fun source()=MemoryNode("video.mp4",ByteArray(4096) { (it%251).toByte() })
    private fun dest()=MemoryNode("destination",directory=true)
    @Test fun presetBoundaries() { val f=SearchFilter(); assertTrue(f.recursive); assertTrue(f.videosOnly); assertNull(f.maxBytes); assertNull(f.after); assertNull(f.before); assertFalse(f.matches("a.mp4",499_999_999,0)); assertTrue(f.matches("a.MKV",500_000_000,0)); assertFalse(f.matches("a.txt",900_000_000,0)) }
    @Test fun dateAndMax() { val f=SearchFilter(minBytes=1,maxBytes=5,after=10,before=20); assertTrue(f.matches("a.mp4",5,20)); assertFalse(f.matches("a.mp4",6,20)); assertFalse(f.matches("a.mp4",5,21)) }
    @Test fun copyPreservesSource() { val s=source(); val d=dest(); FileTasks.transfer(listOf(Snapshot(s)),d,false,AtomicBoolean(false)) {}; assertFalse(s.removed); assertEquals(1,d.children().size); assertEquals(s.name,d.children()[0].name); assertArrayEquals(FileTasks.hash(s,AtomicBoolean(false)),FileTasks.hash(d.children()[0],AtomicBoolean(false))) }
    @Test fun moveDeletesOnlyVerifiedSource() { val s=source(); val d=dest(); FileTasks.transfer(listOf(Snapshot(s)),d,true,AtomicBoolean(false)) {}; assertTrue(s.removed); assertEquals(1,d.children().size) }
    @Test fun corruptionPreservesSourceAndCleansTemp() { val s=source(); val d=dest().apply { corrupt=true }; val log=mutableListOf<String>(); FileTasks.transfer(listOf(Snapshot(s)),d,true,AtomicBoolean(false)) { log.add(it) }; assertFalse(s.removed); assertTrue(d.children().isEmpty()); assertTrue(log.last().contains("중단")) }
    @Test fun cancellationPreservesSource() { val s=source(); val d=dest(); FileTasks.transfer(listOf(Snapshot(s)),d,true,AtomicBoolean(true)) {}; assertFalse(s.removed); assertTrue(d.children().isEmpty()) }
    @Test fun changedSnapshotBlocked() { val s=source(); val snapshot=Snapshot(s); s.timestamp++; blocked { FileTasks.preflight(listOf(snapshot),dest(),true) }; assertFalse(s.removed) }
    @Test fun duplicateBlocked() { val d=dest(); d.entries.add(source()); blocked { FileTasks.preflight(listOf(Snapshot(source())),d,false) } }
    @Test fun repeatedSourceNameBlocked() { blocked { FileTasks.preflight(listOf(Snapshot(source()),Snapshot(source())),dest(),false) } }
    @Test fun insufficientSpaceBlocked() { blocked { FileTasks.preflight(listOf(Snapshot(source())),dest().apply { free=1 },false) } }
    @Test fun unknownCapacityBlocked() { blocked { FileTasks.preflight(listOf(Snapshot(source())),dest().apply { free=-1 },false) } }
    @Test fun readOnlyDestinationBlocked() { blocked { FileTasks.preflight(listOf(Snapshot(source())),dest().apply { write=false },false) } }
    @Test fun readOnlySourceCannotMove() { blocked { FileTasks.preflight(listOf(Snapshot(source().apply { write=false })),dest(),true) } }
    @Test fun recursiveSearchAndCancellation() { val root=dest(); val folder=dest(); folder.entries.add(source()); root.entries.add(folder); assertEquals(1,FileTasks.search(root,SearchFilter(minBytes=0),AtomicBoolean(false)) {}.size); assertEquals(0,FileTasks.search(root,SearchFilter(recursive=false,minBytes=0),AtomicBoolean(false)) {}.size); blocked { FileTasks.search(root,SearchFilter(minBytes=0),AtomicBoolean(true)) {} } }
    private fun blocked(action:()->Unit) { try { action(); fail("Expected operation to be blocked") } catch(e:IllegalArgumentException) {} catch(e:IllegalStateException) {} }
}
