package org.voicelocal.lab;

import org.junit.*;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class LocalSocksProxyTest {
    private LocalSocksProxy proxy;
    private final AtomicInteger protectedTcp=new AtomicInteger(),protectedUdp=new AtomicInteger();
    private final List<String> events=new CopyOnWriteArrayList<>();
    private void start(boolean prelude)throws Exception {
        proxy=new LocalSocksProxy("tester","secret",prelude,new LocalSocksProxy.Protector(){
            public boolean tcp(Socket s){assertTrue("Android protect requires a descriptor created by local bind",s.isBound());assertTrue(s.getLocalPort()>0);assertFalse(s.isConnected());protectedTcp.incrementAndGet();return true;}
            public boolean udp(DatagramSocket s){assertFalse(s.isBound());protectedUdp.incrementAndGet();return true;}
        },new LocalSocksProxy.Events(){public void log(String s){events.add(s);}public void discovery(boolean reply,String endpoint){events.add(reply?"reply":"request");}});
    }
    @After public void clean(){if(proxy!=null)proxy.close();}
    private Socket auth(String password)throws Exception {
        Socket s=new Socket("127.0.0.1",proxy.port());s.setSoTimeout(3000);
        OutputStream out=s.getOutputStream();DataInputStream in=new DataInputStream(s.getInputStream());
        out.write(new byte[]{5,1,2});assertEquals(5,in.readUnsignedByte());assertEquals(2,in.readUnsignedByte());
        byte[] p=password.getBytes(StandardCharsets.UTF_8);out.write(new byte[]{1,6});out.write("tester".getBytes(StandardCharsets.UTF_8));out.write(p.length);out.write(p);out.flush();
        assertEquals(1,in.readUnsignedByte());int code=in.readUnsignedByte();
        if(!password.equals("secret")){assertEquals(1,code);assertEquals(-1,in.read());}else assertEquals(0,code);
        return s;
    }
    private InetSocketAddress request(Socket s,int command,InetSocketAddress target)throws Exception {
        byte[] address=LocalSocksProxy.encodeUdp(target,new byte[0]);OutputStream out=s.getOutputStream();
        out.write(new byte[]{5,(byte)command,0});out.write(address,3,address.length-3);out.flush();
        DataInputStream in=new DataInputStream(s.getInputStream());assertEquals(5,in.readUnsignedByte());assertEquals(0,in.readUnsignedByte());assertEquals(0,in.readUnsignedByte());
        return LocalSocksProxy.readAddress(in);
    }
    private LocalSocksProxy.UdpMessage exchange(DatagramSocket client,InetSocketAddress relay,InetSocketAddress target,byte[] body)throws Exception {
        byte[] wire=LocalSocksProxy.encodeUdp(target,body);client.send(new DatagramPacket(wire,wire.length,relay));
        byte[] buffer=new byte[2048];DatagramPacket result=new DatagramPacket(buffer,buffer.length);client.receive(result);
        return LocalSocksProxy.decodeUdp(Arrays.copyOf(buffer,result.getLength()));
    }
    private static final class Echo implements AutoCloseable {
        final DatagramSocket socket;
        final List<byte[]> seen=new CopyOnWriteArrayList<>();
        final List<Long> times=new CopyOnWriteArrayList<>();
        Echo(boolean discovery)throws Exception {this(discovery,"127.0.0.1");}
        Echo(boolean discovery,String bind)throws Exception {
            socket=new DatagramSocket(new InetSocketAddress(bind,0));
            Thread thread=new Thread(()->{byte[] buf=new byte[2048];while(!socket.isClosed())try{
                DatagramPacket p=new DatagramPacket(buf,buf.length);socket.receive(p);byte[] data=Arrays.copyOf(buf,p.getLength());seen.add(data.clone());times.add(System.nanoTime());
                if(discovery){if(!Packet.discovery(data,1))continue;Packet.put(data,0,2);}
                socket.send(new DatagramPacket(data,data.length,p.getSocketAddress()));
            }catch(IOException ignored){break;}},"test-echo");thread.setDaemon(true);thread.start();
        }
        InetSocketAddress endpoint(){return (InetSocketAddress)socket.getLocalSocketAddress();}
        public void close(){socket.close();}
    }
    @Test public void rejectsWrongPasswordAndNoAuthMethod()throws Exception {
        start(false);try(Socket wrong=auth("bad")){}
        try(Socket noAuth=new Socket("127.0.0.1",proxy.port())){noAuth.setSoTimeout(3000);noAuth.getOutputStream().write(new byte[]{5,1,0});assertEquals(5,noAuth.getInputStream().read());assertEquals(255,noAuth.getInputStream().read());}
        assertEquals(0,protectedTcp.get());assertEquals(0,protectedUdp.get());
    }
    @Test public void tcpHalfClosePreservesResponseAndProtectsBeforeConnect()throws Exception {
        tcpHalfClose("127.0.0.1");
    }
    @Test public void tcpIpv6WorksWithBoundDescriptorBeforeProtection()throws Exception {
        tcpHalfClose("::1");
    }
    private void tcpHalfClose(String host)throws Exception {
        start(false);
        try(ServerSocket target=new ServerSocket(0,1,InetAddress.getByName(host));Socket client=auth("secret")){
            CompletableFuture<byte[]> received=new CompletableFuture<>();
            Thread echo=new Thread(()->{try(Socket s=target.accept()){
                byte[] all=s.getInputStream().readAllBytes();received.complete(all);s.getOutputStream().write("response after EOF".getBytes(StandardCharsets.UTF_8));
            }catch(Exception e){received.completeExceptionally(e);}});echo.setDaemon(true);echo.start();
            request(client,1,new InetSocketAddress(host,target.getLocalPort()));
            client.getOutputStream().write("hello".getBytes(StandardCharsets.UTF_8));client.shutdownOutput();
            assertEquals("response after EOF",new String(client.getInputStream().readAllBytes(),StandardCharsets.UTF_8));
            assertEquals("hello",new String(received.get(3,TimeUnit.SECONDS),StandardCharsets.UTF_8));assertEquals(1,protectedTcp.get());
        }
    }
    @Test public void twoAssociationsAndTwoDestinationsStayIsolated()throws Exception {
        start(false);
        try(Echo a=new Echo(false);Echo b=new Echo(false);Socket c1=auth("secret");Socket c2=auth("secret");DatagramSocket u1=new DatagramSocket(new InetSocketAddress("127.0.0.1",0));DatagramSocket u2=new DatagramSocket(new InetSocketAddress("127.0.0.1",0))){
            u1.setSoTimeout(3000);u2.setSoTimeout(3000);
            InetSocketAddress r1=request(c1,3,new InetSocketAddress("0.0.0.0",0)),r2=request(c2,3,new InetSocketAddress("0.0.0.0",0));
            assertNotEquals(r1,r2);
            assertArrayEquals(new byte[]{1,2},exchange(u1,r1,a.endpoint(),new byte[]{1,2}).payload);
            assertArrayEquals(new byte[]{3},exchange(u2,r2,a.endpoint(),new byte[]{3}).payload);
            LocalSocksProxy.UdpMessage result=exchange(u1,r1,b.endpoint(),new byte[]{4});assertEquals(b.endpoint(),result.destination);assertArrayEquals(new byte[]{4},result.payload);
            assertEquals(2,protectedUdp.get());
        }
    }
    @Test public void preludeRunsOnceOnFirstDiscoveryEvenAfterOtherTraffic()throws Exception {
        start(true);
        try(Echo server=new Echo(true);Socket control=auth("secret");DatagramSocket udp=new DatagramSocket(new InetSocketAddress("127.0.0.1",0))){
            udp.setSoTimeout(3000);InetSocketAddress relay=request(control,3,new InetSocketAddress("0.0.0.0",0));
            byte[] initial=LocalSocksProxy.encodeUdp(server.endpoint(),new byte[]{9});udp.send(new DatagramPacket(initial,initial.length,relay));
            byte[] request=new byte[74];Packet.put(request,0,1);Packet.put(request,2,70);request[7]=44;
            byte[] response=exchange(udp,relay,server.endpoint(),request).payload;assertTrue(Packet.matchesDiscovery(request,response));
            assertArrayEquals(new byte[]{9},server.seen.get(0));assertArrayEquals(new byte[]{0},server.seen.get(1));assertArrayEquals(new byte[]{1},server.seen.get(2));assertArrayEquals(request,server.seen.get(3));
            assertTrue(server.times.get(3)-server.times.get(2)>=TimeUnit.MILLISECONDS.toNanos(35));
            exchange(udp,relay,server.endpoint(),request);assertEquals(5,server.seen.size());assertEquals(2,proxy.replies.get());
            assertTrue(events.contains("request"));assertTrue(events.contains("reply"));
        }
    }
    @Test public void controlCloseReleasesAssociationAndAllSockets()throws Exception {
        start(false);Socket control=auth("secret");request(control,3,new InetSocketAddress("0.0.0.0",0));control.close();
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(proxy.trackedResources()!=0&&System.nanoTime()<end)Thread.sleep(10);
        assertEquals(0,proxy.trackedResources());proxy.close();assertEquals(0,proxy.trackedResources());
    }
    @Test public void udpCodecSupportsIpv6AndRejectsFragments()throws Exception {
        InetSocketAddress address=new InetSocketAddress(InetAddress.getByName("2001:db8::5"),50500);byte[] payload=new byte[]{7,8,9};
        byte[] wire=LocalSocksProxy.encodeUdp(address,payload);LocalSocksProxy.UdpMessage m=LocalSocksProxy.decodeUdp(wire);assertEquals(address,m.destination);assertArrayEquals(payload,m.payload);
        wire[2]=1;assertNull(LocalSocksProxy.decodeUdp(wire));assertNull(LocalSocksProxy.decodeUdp(new byte[3]));
        try{LocalSocksProxy.decodeUdp(new byte[]{0,0,0,4,1});fail();}catch(EOFException expected){}
    }
    @Test public void realIpv6UdpRoundTripThroughAuthenticatedAssociation()throws Exception {
        start(false);
        try(Echo echo=new Echo(false,"::1");Socket control=auth("secret");DatagramSocket udp=new DatagramSocket(new InetSocketAddress("127.0.0.1",0))){
            udp.setSoTimeout(3000);InetSocketAddress relay=request(control,3,new InetSocketAddress("0.0.0.0",0));
            byte[] body=new byte[]{11,12,13};LocalSocksProxy.UdpMessage reply=exchange(udp,relay,echo.endpoint(),body);
            assertEquals(echo.endpoint(),reply.destination);assertArrayEquals(body,reply.payload);
        }
    }
    @Test public void failedProtectionDoesNotOpenRemoteConnection()throws Exception {
        proxy=new LocalSocksProxy("tester","secret",false,new LocalSocksProxy.Protector(){public boolean tcp(Socket s){return false;}public boolean udp(DatagramSocket s){return false;}},new LocalSocksProxy.Events(){public void log(String s){}public void discovery(boolean reply,String endpoint){}});
        try(ServerSocket target=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));Socket client=auth("secret")){
            target.setSoTimeout(150);byte[] address=LocalSocksProxy.encodeUdp(new InetSocketAddress("127.0.0.1",target.getLocalPort()),new byte[0]);
            client.getOutputStream().write(new byte[]{5,1,0});client.getOutputStream().write(address,3,address.length-3);
            DataInputStream failure=new DataInputStream(client.getInputStream());assertEquals(5,failure.readUnsignedByte());assertEquals(1,failure.readUnsignedByte());assertEquals(0,failure.readUnsignedByte());LocalSocksProxy.readAddress(failure);assertEquals(-1,failure.read());
            assertEquals(1,proxy.tcpProtectFailures.get());assertEquals("TCP_PROTECT_FAILED",proxy.lastError);
            assertEquals(0,proxy.tcpConnectFailures.get());
            try{target.accept();fail("Protection failure must not connect");}catch(SocketTimeoutException expected){}
        }
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(proxy.trackedResources()!=0&&System.nanoTime()<deadline)Thread.sleep(10);
        assertEquals(0,proxy.trackedResources());
    }
    @Test public void refusedTcpConnectionHasDistinctCounterAndSafeErrorCode()throws Exception {
        start(false);int unused;
        try(ServerSocket target=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){unused=target.getLocalPort();}
        try(Socket client=auth("secret")){
            byte[] a=LocalSocksProxy.encodeUdp(new InetSocketAddress("127.0.0.1",unused),new byte[0]);
            client.getOutputStream().write(new byte[]{5,1,0});client.getOutputStream().write(a,3,a.length-3);
            DataInputStream failure=new DataInputStream(client.getInputStream());assertEquals(5,failure.readUnsignedByte());assertEquals(5,failure.readUnsignedByte());assertEquals(0,failure.readUnsignedByte());LocalSocksProxy.readAddress(failure);assertEquals(-1,failure.read());
            assertEquals(1,proxy.tcpAttempts.get());assertEquals(0,proxy.tcpConnections.get());assertEquals(0,proxy.tcpProtectFailures.get());assertEquals(1,proxy.tcpConnectFailures.get());
            assertEquals("TCP_CONNECT_FAILED",proxy.lastError);
        }
    }
}
