package org.semiproject.pillbadeumserver.common.external;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class PublicDataResponseCacheTests {
    @Test
    void expiresAtFiveMinutesWithoutServingStaleData(){
        var now=new AtomicLong();var cache=new PublicDataResponseCache(now::get);
        var key=PublicDataResponseCache.Key.of("/official",Map.of("itemSeq","100"));
        cache.put(key,"official-response");
        now.set(PublicDataResponseCache.TTL_NANOS-1);assertThat(cache.get(key)).isEqualTo("official-response");
        now.incrementAndGet();assertThat(cache.get(key)).isNull();
    }

    @Test
    void limitsEntriesAndEvictsTheLeastRecentlyUsedResponse(){
        var cache=new PublicDataResponseCache(()->0);
        for(int i=0;i<PublicDataResponseCache.MAX_ENTRIES;i++)cache.put(key(i),"reply");
        assertThat(cache.get(key(0))).isEqualTo("reply");
        cache.put(key(128),"new");
        assertThat(cache.get(key(1))).isNull();assertThat(cache.get(key(0))).isEqualTo("reply");
        assertThat(cache.get(key(128))).isEqualTo("new");
    }

    @Test
    void rejectsOversizedResponses(){
        var cache=new PublicDataResponseCache();
        cache.put(key(0),"x".repeat(PublicDataResponseCache.MAX_RESPONSE_CHARS+1));
        assertThat(cache.get(key(0))).isNull();
    }

    @Test
    void invalidLookupDropsOnlyThatItemsPagesAndKeepsOtherProductsAndServices(){
        var cache=new PublicDataResponseCache();
        var page1=PublicDataResponseCache.Key.of("/age",Map.of("itemSeq","A","pageNo",1));
        var page2=PublicDataResponseCache.Key.of("/age",Map.of("itemSeq","A","pageNo",2));
        var other=PublicDataResponseCache.Key.of("/age",Map.of("itemSeq","B","pageNo",1));
        var differentService=PublicDataResponseCache.Key.of("/permit",Map.of("itemSeq","A","pageNo",1));
        for(var key:java.util.List.of(page1,page2,other,differentService))cache.put(key,"reply");
        cache.discard(PublicDataResponseCache.Key.of("/age",Map.of("itemSeq","A")));
        assertThat(cache.get(page1)).isNull();assertThat(cache.get(page2)).isNull();
        assertThat(cache.get(other)).isEqualTo("reply");assertThat(cache.get(differentService)).isEqualTo("reply");
    }

    @Test
    void keySnapshotsEffectiveFiltersAndDoesNotContainCredentials(){
        var params=new HashMap<String,Object>();params.put("itemSeq","100");params.put("pageNo",1);
        params.put("serviceKey","synthetic-secret");params.put("type","ignored");params.put("unused",null);
        var key=PublicDataResponseCache.Key.of("/official",params);
        params.put("itemSeq","200");
        assertThat(key).isEqualTo(PublicDataResponseCache.Key.of("/official",Map.of("pageNo","1","itemSeq","100")));
        assertThat(key.toString()).doesNotContain("synthetic-secret","serviceKey");
        assertThat(key).isNotEqualTo(PublicDataResponseCache.Key.of("/other",Map.of("pageNo",1,"itemSeq","100")));
        assertThat(key).isNotEqualTo(PublicDataResponseCache.Key.of("/official",Map.of("pageNo",2,"itemSeq","100")));
    }
    private PublicDataResponseCache.Key key(int i){return PublicDataResponseCache.Key.of("/official",Map.of("itemSeq",i));}
}
