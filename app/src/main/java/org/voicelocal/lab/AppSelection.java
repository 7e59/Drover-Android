package org.voicelocal.lab;

import java.util.*;

final class AppSelection {
    static final String[] PACKAGES={"com.discord","com.instagram.android","com.snapchat.android","org.thoughtcrime.securesms"};
    static final String[] NAMES={"Discord","Instagram","Snapchat","Signal"};
    static List<String> validate(Collection<String> input){
        if(input==null||input.isEmpty())throw new IllegalArgumentException("اختر تطبيقاً واحداً على الأقل");
        Set<String> known=new HashSet<>(Arrays.asList(PACKAGES));
        for(String name:input)if(!known.contains(name))throw new IllegalArgumentException("تطبيق غير مدعوم");
        List<String> result=new ArrayList<>();
        for(String name:PACKAGES)if(input.contains(name))result.add(name);
        return Collections.unmodifiableList(result);
    }
    static String names(Collection<String> selected){
        List<String> names=new ArrayList<>();
        for(int i=0;i<PACKAGES.length;i++)if(selected.contains(PACKAGES[i]))names.add(NAMES[i]);
        return names.isEmpty()?"لم تختر تطبيقاً":String.join(" · ",names);
    }
}
