package org.voicelocal.lab;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
public class AppSelectionTest {
    @Test public void emptyOrUnknownSelectionFailsClosed(){
        for(Collection<String> input:Arrays.asList(null,Collections.<String>emptyList(),Collections.singletonList("unknown.app"),Arrays.asList("com.discord","unknown.app"))){
            try{AppSelection.validate(input);fail("Must not allow all-app VPN fallback");}catch(IllegalArgumentException expected){}
        }
    }
    @Test public void supportsOtherAppsWithoutDiscordAndDeduplicates(){
        assertEquals(Arrays.asList("com.snapchat.android","org.thoughtcrime.securesms"),AppSelection.validate(Arrays.asList("org.thoughtcrime.securesms","com.snapchat.android","com.snapchat.android")));
    }
    @Test public void namesAndAllFourPackagesMatch(){
        assertEquals(4,AppSelection.validate(Arrays.asList(AppSelection.PACKAGES)).size());
        assertEquals("Instagram · Signal",AppSelection.names(Arrays.asList("com.instagram.android","org.thoughtcrime.securesms")));
    }
}
