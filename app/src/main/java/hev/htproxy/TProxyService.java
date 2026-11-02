package hev.htproxy;

/** The upstream JNI registration contract. This is a bridge, not an Android Service. */
public final class TProxyService {
    static { System.loadLibrary("hev-socks5-tunnel"); }
    public native boolean TProxyStartService(String configurationPath,int tunFd);
    public native boolean TProxyStopService();
    public native boolean TProxyIsRunning();
    public native long[] TProxyGetStats();
}
