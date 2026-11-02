package org.voicelocal.lab;

import java.util.ArrayDeque;
import java.util.Locale;
import java.text.SimpleDateFormat;
import java.util.Date;

final class Journal {
    private static final ArrayDeque<String> rows=new ArrayDeque<>();
    static volatile boolean running=false;
    static volatile String state="متوقف";
    static synchronized void add(String text) {
        while(rows.size()>=100) rows.removeFirst();
        rows.addLast(new SimpleDateFormat("HH:mm:ss",Locale.ROOT).format(new Date())+"  "+text);
    }
    static synchronized String text() { return String.join("\n",rows); }
    static synchronized void clear() { rows.clear(); }
}
