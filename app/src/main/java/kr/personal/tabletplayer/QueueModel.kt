package kr.personal.tabletplayer

/** Metadata is captured once so sorting never performs storage I/O on the UI thread. */
data class QueueEntry(val id:String,val name:String,val size:Long=0,val date:Long=0)
object QueueModel {
    fun unique(items:List<QueueEntry>)=items.distinctBy { it.id }
    fun sorted(items:List<QueueEntry>,key:Int,descending:Boolean):List<QueueEntry> {
        val comparator=when(key) {
            1 -> compareBy<QueueEntry> { it.size }.thenBy { it.name.lowercase(java.util.Locale.ROOT) }.thenBy { it.id }
            2 -> compareBy<QueueEntry> { it.date }.thenBy { it.name.lowercase(java.util.Locale.ROOT) }.thenBy { it.id }
            else -> compareBy<QueueEntry> { it.name.lowercase(java.util.Locale.ROOT) }.thenBy { it.id }
        }
        return items.sortedWith(if(descending) comparator.reversed() else comparator)
    }
}
