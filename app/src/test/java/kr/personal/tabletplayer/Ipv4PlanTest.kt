package kr.personal.tabletplayer
import org.junit.Assert.*
import org.junit.Test
class Ipv4PlanTest {
    private fun ip(vararg n:Int)=n.map { it.toByte() }.toByteArray()
    @Test fun wifiSubnetExcludesOwnNetworkAndBroadcast() {
        val hosts=Ipv4Plan.hosts(ip(192,168,1,42),24)
        assertEquals(253,hosts.size); assertTrue("192.168.1.1" in hosts)
        assertFalse("192.168.1.0" in hosts); assertFalse("192.168.1.255" in hosts); assertFalse("192.168.1.42" in hosts)
    }
    @Test fun largeSubnetIsBoundedAndContainsLocalSegment() {
        val hosts=Ipv4Plan.hosts(ip(10,5,6,7),16)
        assertEquals(1021,hosts.size); assertTrue("10.5.6.8" in hosts); assertFalse("10.5.8.1" in hosts)
    }
    @Test fun refusesPublicAndInvalidRanges() {
        assertTrue(Ipv4Plan.hosts(ip(8,8,8,8),24).isEmpty()); assertTrue(Ipv4Plan.hosts(ip(192,168,1,2),0).isEmpty())
        assertTrue(Ipv4Plan.hosts(ip(192,168,1,2),32).isEmpty()); assertFalse(Ipv4Plan.local(ip(172,32,0,1)))
    }
    @Test fun advertisedServersMustMatchWifiSubnet() {
        assertTrue(Ipv4Plan.contains(ip(192,168,1,42),24,ip(192,168,1,100)))
        assertFalse(Ipv4Plan.contains(ip(192,168,1,42),24,ip(192,168,2,100)))
    }
    @Test fun honorsSmallSubnetAndLinkLocal() {
        assertEquals(listOf("172.16.0.2"),Ipv4Plan.hosts(ip(172,16,0,1),30))
        assertTrue(Ipv4Plan.local(ip(169,254,1,2)))
    }
}
