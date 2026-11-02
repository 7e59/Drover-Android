package org.voicelocal.lab;

import java.util.Arrays;

/** Independent minimal IPv4/UDP codec. No TCP stack or IP reassembly. */
final class Packet {
    final byte[] src, dst, data;
    final int sport, dport;
    Packet(byte[] src, byte[] dst, int sport, int dport, byte[] data) {
        this.src = src.clone(); this.dst = dst.clone(); this.sport = sport; this.dport = dport; this.data = data.clone();
    }
    static int u16(byte[] b, int i) { return ((b[i] & 255) << 8) | (b[i+1] & 255); }
    static void put(byte[] b, int i, int v) { b[i]=(byte)(v>>>8); b[i+1]=(byte)v; }
    static byte[] address(String s) {
        String[] parts = s.trim().split("\\.", -1);
        if (parts.length != 4) throw new IllegalArgumentException("أدخل IPv4 رقمياً لخادم الصوت، دون رابط أو توكن");
        byte[] ip = new byte[4];
        for (int i=0;i<4;i++) {
            if (!parts[i].matches("[0-9]{1,3}")) throw new IllegalArgumentException("عنوان IPv4 غير صالح");
            int v = Integer.parseInt(parts[i]);
            if (v>255) throw new IllegalArgumentException("عنوان IPv4 غير صالح");
            ip[i]=(byte)v;
        }
        int a=ip[0]&255, b=ip[1]&255;
        if (a==0 || a==10 || a==127 || a>=224 || (a==169&&b==254) || (a==172&&b>=16&&b<=31) || (a==192&&b==168) || (a==100&&b>=64&&b<=127))
            throw new IllegalArgumentException("استخدم عنوان خادم صوت عاماً، لا عنوان الشبكة المحلية");
        return ip;
    }
    static Packet parse(byte[] b, int n) {
        if(n<28 || n>b.length || (b[0]&240)!=64) return null;
        int h=(b[0]&15)*4, total=u16(b,2);
        if(h<20 || h+8>n || total!=n || (b[9]&255)!=17 || (u16(b,6)&0xbfff)!=0 || checksum(b,0,h,0)!=0) return null;
        int len=u16(b,h+4);
        if(len<8 || h+len!=total || u16(b,h)==0 || u16(b,h+2)==0) return null;
        if(u16(b,h+6)!=0 && checksum(b,h,len,pseudo(b,len))!=0) return null;
        return new Packet(Arrays.copyOfRange(b,12,16), Arrays.copyOfRange(b,16,20), u16(b,h),u16(b,h+2),Arrays.copyOfRange(b,h+8,total));
    }
    static int pseudo(byte[] b,int len) { return u16(b,12)+u16(b,14)+u16(b,16)+u16(b,18)+17+len; }
    static int checksum(byte[] b,int start,int len,int sum) {
        int end=start+len;
        for(int i=start;i<end;i+=2) sum += (b[i]&255)*256+(i+1<end?(b[i+1]&255):0);
        while((sum>>>16)!=0) sum=(sum&65535)+(sum>>>16);
        return (~sum)&65535;
    }
    byte[] encode() {
        if(data.length>65507) throw new IllegalArgumentException("UDP too large");
        byte[] b=new byte[28+data.length]; b[0]=0x45; put(b,2,b.length); b[8]=64; b[9]=17;
        System.arraycopy(src,0,b,12,4); System.arraycopy(dst,0,b,16,4);
        put(b,10,checksum(b,0,20,0)); put(b,20,sport); put(b,22,dport); put(b,24,8+data.length);
        System.arraycopy(data,0,b,28,data.length);
        int sum=checksum(b,20,8+data.length,pseudo(b,8+data.length)); put(b,26,sum==0?65535:sum);
        return b;
    }
    static boolean discovery(byte[] b,int type) {
        return b.length==74 && u16(b,0)==type && u16(b,2)==70;
    }
    static boolean matchesDiscovery(byte[] request,byte[] response) {
        if(!discovery(request,1)||!discovery(response,2)) return false;
        for(int i=4;i<8;i++) if(request[i]!=response[i]) return false;
        return true;
    }
    static boolean matchesSsrc(byte[] ssrc,byte[] response) {
        if(ssrc.length!=4 || !discovery(response,2)) return false;
        for(int i=0;i<4;i++) if(ssrc[i]!=response[i+4]) return false;
        return true;
    }
}
