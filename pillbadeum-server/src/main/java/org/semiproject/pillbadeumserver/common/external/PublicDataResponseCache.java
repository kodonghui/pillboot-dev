package org.semiproject.pillbadeumserver.common.external;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.LongSupplier;

/** 같은 정상 공공 응답만 짧게 재사용함. 디스크·DB·백그라운드 갱신·만료 후 대체 사용은 없음. */
final class PublicDataResponseCache {
    static final long TTL_NANOS=Duration.ofMinutes(5).toNanos();
    static final int MAX_ENTRIES=128;
    static final int MAX_RESPONSE_CHARS=128*1024;
    private final Map<Key,Entry> entries=new LinkedHashMap<>(16,.75f,true);
    private final LongSupplier now;

    PublicDataResponseCache(){this(System::nanoTime);}
    PublicDataResponseCache(LongSupplier now){this.now=now;}

    synchronized String get(Key key){
        Entry entry=entries.get(key);
        if(entry==null)return null;
        if(now.getAsLong()-entry.savedAt()>=TTL_NANOS){entries.remove(key);return null;}
        return entry.body();
    }

    synchronized void put(Key key,String body){
        if(body.length()>MAX_RESPONSE_CHARS)return;
        entries.put(key,new Entry(body,now.getAsLong()));
        if(entries.size()>MAX_ENTRIES)entries.remove(entries.keySet().iterator().next());
    }

    synchronized void discard(Key identity){
        entries.keySet().removeIf(key->key.path().equals(identity.path())
                &&key.parameters().entrySet().containsAll(identity.parameters().entrySet()));
    }

    record Key(String path,Map<String,String> parameters){
        static Key of(String path,Map<String,?> parameters){
            var normalized=new TreeMap<String,String>();
            parameters.forEach((name,value)->{
                if(value!=null&&!name.equals("serviceKey")&&!name.equals("type"))normalized.put(name,value.toString());
            });
            return new Key(path,Map.copyOf(normalized));
        }
    }
    private record Entry(String body,long savedAt){}
}
