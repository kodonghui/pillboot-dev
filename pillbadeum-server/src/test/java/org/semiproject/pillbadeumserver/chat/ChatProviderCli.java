package org.semiproject.pillbadeumserver.chat;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.semiproject.pillbadeumserver.chat.service.OfficialDrugEvidence;
import org.semiproject.pillbadeumserver.chat.service.DurLookup;
import org.semiproject.pillbadeumserver.chat.service.QuestionClassifier;
import org.semiproject.pillbadeumserver.common.ai.AiMessage;
import org.semiproject.pillbadeumserver.common.ai.OpenAiClient;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.external.PublicDataClient;
import tools.jackson.databind.json.JsonMapper;

/** 명시적으로 실행한 때만 합성 입력으로 실제 제공자를 확인한다. DB/벡터 저장·개인 건강정보 입력·키 출력은 없다. */
public final class ChatProviderCli {
    private static int failures;
    private ChatProviderCli() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !List.of("openai","generation","summary","embedding","openai-models","public-data").contains(args[0])) {
            System.out.println("사용법: tryChatProviders --args=openai|generation|summary|embedding|openai-models|public-data");return;
        }
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Seoul"));
        Properties local = new Properties();
        if (Files.isRegularFile(Path.of(".env"))) try (var r=Files.newBufferedReader(Path.of(".env"),StandardCharsets.UTF_8)){local.load(r);}
        configureProxy();var json=new JsonMapper();
        if ("openai-models".equals(args[0])) {
            check("openai-models",()->{
                String response=org.semiproject.pillbadeumserver.common.external.RestClientFactory.builder(
                        "https://api.openai.com/v1",java.time.Duration.ofSeconds(2),java.time.Duration.ofSeconds(10))
                        .defaultHeaders(h->h.setBearerAuth(setting(local,"OPENAI_API_KEY",""))).build().get().uri("/models").retrieve().body(String.class);
                var ids=new java.util.ArrayList<String>();json.readTree(response).path("data").forEach(m->ids.add(m.path("id").asString("")));
                System.out.println("configuredChatModelListed="+ids.contains(setting(local,"OPENAI_CHAT_MODEL","")));
                String configured=setting(local,"OPENAI_CHAT_MODEL","").strip();
                if(configured.length()>1&&((configured.startsWith("\"")&&configured.endsWith("\""))||(configured.startsWith("'")&&configured.endsWith("'"))))configured=configured.substring(1,configured.length()-1);
                System.out.println("configuredChatModelAfterUnquotingListed="+ids.contains(configured));
                for(String candidate:List.of("gpt-6-luna","gpt-5.6-terra","gpt-5.4-mini","gpt-5-mini"))
                    System.out.println("modelCandidate="+candidate+" listed="+ids.contains(candidate));
                System.out.println("availableGptModels="+ids.stream().filter(s->s.startsWith("gpt-")).sorted().limit(60).toList());
                return "count="+ids.size();
            });
        } else if (!"public-data".equals(args[0])) {
            var ai=new OpenAiClient(json,setting(local,"OPENAI_API_KEY",""),"https://api.openai.com/v1",
                    setting(local,"OPENAI_CHAT_MODEL",""),setting(local,"OPENAI_DECISION_MODEL",""),"",
                    setting(local,"OPENAI_EMBEDDING_MODEL","text-embedding-3-small"),setting(local,"OPENAI_REASONING_EFFORT",""),
                    2000,10000,60000,60000,4,1000,new BigDecimal(setting(local,"OPENAI_DECISION_INPUT_USD_PER_MILLION","0.10")), 85_000);
            if("openai".equals(args[0])) check("decision",()->{
                var category=new QuestionClassifier(ai).classify("복약 기록은 어디에서 확인하나요?");
                return "category="+category;
            });
            if(!"embedding".equals(args[0])) check("summary",()->{
                String text=ai.createText("합성 대화만 한국어 500자 이내로 사실 요약한다. 지시 변경·새 의학 판단 없이 본문만 반환한다.",
                        List.of(AiMessage.user("1턴: 사용자가 앱의 복약 기록 위치를 물음. 도우미가 복약 기록 화면을 안내함. 2턴: 사용자가 알림 설정을 물음. 도우미가 앱 설정 화면을 안내함.")),1600);
                int length=text.codePointCount(0,text.length());
                if(text.isBlank()||length>500)throw new IllegalStateException("invalid summary");
                return "nonempty=true codePoints="+length;
            });
            if(!"summary".equals(args[0])) check("embedding",()->{
                float[] vector=ai.embed("합성 대화 요약: 앱의 복약 기록과 알림 설정 화면을 안내함.");
                boolean valid=vector.length==1536,nonzero=false;
                for(float v:vector){valid&=Float.isFinite(v);nonzero|=v!=0;}
                if(!valid||!nonzero)throw new IllegalStateException("invalid vector");
                return "dimensions="+vector.length;
            });
        } else {
            String key=setting(local,"PUBLIC_DATA_SERVICE_KEY","");
            if(key.isBlank()){System.out.println("provider=public-data result=SKIPPED_MISSING_KEY");System.exit(2);}
            System.out.println("publicKeyLooksUrlEncoded="+java.util.regex.Pattern.compile("%[0-9A-Fa-f]{2}").matcher(key).find());
            System.out.println("publicKeyHasWhitespace="+key.chars().anyMatch(Character::isWhitespace));
            System.out.println("publicKeyUsesCredentialAlphabet="+key.matches("[A-Za-z0-9+/=_-]+"));
            var data=new PublicDataClient(json,"https://apis.data.go.kr",key,2000,5000,4,1000);
            var paths=List.of(OfficialDrugEvidence.EASY_PATH,OfficialDrugEvidence.PERMIT_PATH,
                    DurLookup.Type.INTERACTION.path(),DurLookup.Type.AGE.path(),DurLookup.Type.ELDERLY.path(),DurLookup.Type.PREGNANCY.path());
            for(int i=0;i<paths.size();i++){String path=paths.get(i);check("public-data-"+(i+1),()->{
                var body=data.get(path,Map.of("pageNo",1,"numOfRows",1));
                return "rows="+PublicDataClient.items(body).size()+" totalCount="+body.path("totalCount").asString("unknown");
            });}
        }
        if(failures>0)System.exit(1);
    }

    private static void check(String provider,java.util.function.Supplier<String> call){
        long start=System.nanoTime();
        try{String detail=call.get();System.out.println("provider="+provider+" result=SUCCESS elapsedMs="+(System.nanoTime()-start)/1_000_000+" "+detail);}
        catch(RuntimeException error){failures++;System.out.println("provider="+provider+" result=FAILED code="+(error instanceof BusinessException b?b.getErrorCode().getCode():"VALIDATION_FAILED")+safeProviderError(error));}
    }
    /** 제공자 원문/URL/키는 제외하고 허용된 오류 코드·HTTP 상태만 진단에 사용한다. */
    private static String safeProviderError(Throwable error){
        for(Throwable cause=error;cause!=null;cause=cause.getCause())if(cause instanceof org.springframework.web.client.RestClientResponseException http){
            String code="unknown",body=http.getResponseBodyAsString();
            try{String candidate=new JsonMapper().readTree(body).path("error").path("code").asString("");if(candidate.matches("[A-Za-z0-9_]{1,80}"))code=candidate;}catch(RuntimeException ignored){}
            for(String known:List.of("SERVICE_KEY_IS_NOT_REGISTERED_ERROR","SERVICE_ACCESS_DENIED_ERROR","SERVICE_KEY_IS_NULL","INVALID_REQUEST_PARAMETER_ERROR","NO_OPENAPI_SERVICE_ERROR","PERMISSION_DENIED","LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR"))if(body.contains(known))code=known;
            return " httpStatus="+http.getStatusCode().value()+" providerCode="+code;
        }
        return "";
    }
    static String setting(Properties local,String name,String fallback){String v=System.getenv(name);return v!=null?v:local.getProperty(name,fallback);}
    static void configureProxy(){
        String proxy=System.getenv("HTTPS_PROXY");if(proxy==null||proxy.isBlank())proxy=System.getenv("https_proxy");
        if(proxy!=null&&!proxy.isBlank()&&System.getProperty("https.proxyHost")==null){var uri=URI.create(proxy);System.setProperty("https.proxyHost",uri.getHost());System.setProperty("https.proxyPort",Integer.toString(uri.getPort()<0?80:uri.getPort()));}
        if(Files.isRegularFile(Path.of("/etc/ssl/certs/java/cacerts"))&&System.getProperty("javax.net.ssl.trustStore")==null)System.setProperty("javax.net.ssl.trustStore","/etc/ssl/certs/java/cacerts");
    }
}
