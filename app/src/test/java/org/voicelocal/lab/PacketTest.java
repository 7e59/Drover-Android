package org.voicelocal.lab;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Random;

public class PacketTest {
    private Packet packet(byte[] data) { return new Packet(new byte[]{10,77,0,1},new byte[]{1,1,1,1},12345,50000,data); }
    private void ipChecksum(byte[] b) { Packet.put(b,10,0); Packet.put(b,10,Packet.checksum(b,0,20,0)); }
    @Test public void roundTripsOddEvenAndEmptyPayloads() {
        Random r=new Random(19);
        for(int len:new int[]{0,1,2,73,74,1472,65507}) {
            byte[] data=new byte[len]; r.nextBytes(data); byte[] wire=packet(data).encode();
            Packet decoded=Packet.parse(wire,wire.length); assertNotNull(decoded);
            assertArrayEquals(data,decoded.data); assertEquals(12345,decoded.sport); assertEquals(50000,decoded.dport);
            assertEquals(0,Packet.checksum(wire,0,20,0));
            assertEquals(0,Packet.checksum(wire,20,len+8,Packet.pseudo(wire,len+8)));
        }
    }
    @Test public void knownIndependentIPv4ChecksumVector() {
        byte[] h=new byte[]{0x45,0,0,0x73,0,0,0x40,0,0x40,0x11,0,0,(byte)0xc0,(byte)0xa8,0,1,(byte)0xc0,(byte)0xa8,0,(byte)0xc7};
        assertEquals(0xb861,Packet.checksum(h,0,h.length,0));
    }
    @Test public void rejectsTruncatedOrInconsistentPackets() {
        byte[] b=packet(new byte[]{1,2,3}).encode();
        for(int n=0;n<b.length;n++) assertNull(Packet.parse(b,n));
        Packet.put(b,24,8); assertNull(Packet.parse(b,b.length));
    }
    @Test public void rejectsCorruptionAndAllowsIPv4ZeroUdpChecksum() {
        byte[] b=packet(new byte[]{1,2,3}).encode(); b[28]^=1; assertNull(Packet.parse(b,b.length));
        Packet.put(b,26,0); assertNotNull(Packet.parse(b,b.length));
        b[12]^=1; assertNull(Packet.parse(b,b.length));
    }
    @Test public void rejectsFragmentsButAcceptsDontFragment() {
        for(int flags:new int[]{0x2000,1,0x8000}) {
            byte[] b=packet(new byte[]{0}).encode(); Packet.put(b,6,flags); ipChecksum(b); assertNull(Packet.parse(b,b.length));
        }
        byte[] b=packet(new byte[]{0}).encode(); Packet.put(b,6,0x4000); ipChecksum(b); assertNotNull(Packet.parse(b,b.length));
    }
    @Test public void discoveryRequiresHeaderAndMatchingSsrc() {
        byte[] req=new byte[74]; Packet.put(req,0,1); Packet.put(req,2,70); req[7]=42;
        byte[] rep=req.clone(); Packet.put(rep,0,2);
        assertTrue(Packet.matchesDiscovery(req,rep)); rep[7]=41; assertFalse(Packet.matchesDiscovery(req,rep));
        req[3]=0; assertFalse(Packet.discovery(req,1)); assertFalse(Packet.discovery(new byte[74],1));
    }
    @Test public void rejectsLocalAndInvalidAddressInputs() {
        for(String s:new String[]{"10.0.0.1","127.0.0.1","192.168.1.1","224.0.0.1","1.2.3.999","1.2.3","discord.com","1.2.3.-1","https://x","100.64.0.1"}) {
            try { Packet.address(s); fail(s); } catch(IllegalArgumentException expected) { }
        }
        assertArrayEquals(new byte[]{1,1,1,1},Packet.address("1.1.1.1"));
    }
    @Test public void arbitraryMalformedInputsNeverCrashParser() {
        Random r=new Random(317);
        for(int i=0;i<10000;i++) { byte[] b=new byte[r.nextInt(200)]; r.nextBytes(b); Packet.parse(b,b.length); }
    }
    @Test public void computedZeroChecksumIsSentAsAllOnes() {
        Packet p=new Packet(new byte[]{(byte)203,0,113,1},new byte[]{(byte)198,51,100,2},12345,50000,new byte[]{(byte)0xa6,0x18});
        byte[] b=p.encode(); assertEquals(65535,Packet.u16(b,26)); assertNotNull(Packet.parse(b,b.length));
    }
    @Test public void acceptsIpv4OptionsAndRejectsTruncatedOptions() {
        byte[] plain=packet(new byte[]{5,6,7}).encode(); byte[] options=new byte[plain.length+4];
        System.arraycopy(plain,0,options,0,20); System.arraycopy(plain,20,options,24,plain.length-20);
        options[0]=0x46; Packet.put(options,2,options.length); Packet.put(options,10,0);
        Packet.put(options,10,Packet.checksum(options,0,24,0));
        assertNotNull(Packet.parse(options,options.length));
        options[0]=0x4f; assertNull(Packet.parse(options,options.length));
    }
    @Test public void pendingDiscoveryStoresOnlySsrc() {
        byte[] response=new byte[74]; Packet.put(response,0,2); Packet.put(response,2,70); response[7]=99;
        assertTrue(Packet.matchesSsrc(new byte[]{0,0,0,99},response));
        assertFalse(Packet.matchesSsrc(new byte[]{0,0,0,98},response));
        assertFalse(Packet.matchesSsrc(new byte[0],response));
    }
}
