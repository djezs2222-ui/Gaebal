package kr.personal.tabletplayer

/** Only the directly attached Wi-Fi subnet; cap unicast probing at 1022 hosts. */
object Ipv4Plan {
    private fun number(bytes:ByteArray):Long { require(bytes.size==4); return bytes.fold(0L) { n,b -> (n shl 8) or (b.toLong() and 255) } }
    fun local(bytes:ByteArray):Boolean {
        if(bytes.size!=4) return false
        val a=bytes[0].toInt() and 255; val b=bytes[1].toInt() and 255
        return a==10 || a==172 && b in 16..31 || a==192 && b==168 || a==169 && b==254
    }
    fun contains(local:ByteArray,prefix:Int,candidate:ByteArray):Boolean {
        if(local.size!=4 || candidate.size!=4 || prefix !in 1..32) return false
        val mask=(0xffffffffL shl (32-prefix)) and 0xffffffffL
        return (number(local) and mask) == (number(candidate) and mask)
    }
    fun hosts(bytes:ByteArray,prefix:Int):List<String> {
        if(!local(bytes) || prefix !in 1..30) return emptyList()
        val p=prefix.coerceAtLeast(22); val own=number(bytes)
        val mask=(0xffffffffL shl (32-p)) and 0xffffffffL
        val first=own and mask; val last=first or (0xffffffffL xor mask)
        return ((first+1) until last).filter { it!=own }.map { n -> (3 downTo 0).joinToString(".") { shift -> ((n shr (shift*8)) and 255).toString() } }
    }
}
