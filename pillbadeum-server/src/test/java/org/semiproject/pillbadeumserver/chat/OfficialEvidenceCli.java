package org.semiproject.pillbadeumserver.chat;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import org.semiproject.pillbadeumserver.chat.domain.AnswerSource;
import org.semiproject.pillbadeumserver.chat.service.AnswerInput;
import org.semiproject.pillbadeumserver.chat.service.ChatDeadline;
import org.semiproject.pillbadeumserver.chat.service.OfficialDrugEvidence;
import org.semiproject.pillbadeumserver.common.config.TimeZoneConfig;
import org.semiproject.pillbadeumserver.common.external.PublicDataClient;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** CHAT-007: 앱의 실제 근거 수집기로 공개 품목 하나를 점검한다. DB·모델 호출과 본문/키 출력은 없다. */
public final class OfficialEvidenceCli {
    private OfficialEvidenceCli() {}

    /** 품목코드만 인자로 받고 기존 설정을 읽는다. 입력/설정 오류는 요청 전에 종료 코드2로 구분한다. */
    public static void main(String[] args) {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(TimeZoneConfig.ZONE));
        int exit = 2;
        if (args.length != 1 || !validCode(args[0])) {
            System.out.println("사용법: tryOfficialEvidence --args=<9자리 공개 품목기준코드>");
        } else {
            try {
                Properties local = new Properties();
                if (Files.isRegularFile(Path.of(".env"))) {
                    try (var reader = Files.newBufferedReader(Path.of(".env"), StandardCharsets.UTF_8)) { local.load(reader); }
                }
                ChatProviderCli.configureProxy();
                exit = probe(args[0], "https://apis.data.go.kr",
                        ChatProviderCli.setting(local,"PUBLIC_DATA_SERVICE_KEY",""),
                        Path.of("build/local-probes/official-evidence.json"), System.out);
            } catch (Exception exception) {
                // 예외 메시지·환경값·파일 내용은 출력하지 않는다.
                System.out.println("officialEvidence=SETUP_FAILED");
            }
        }
        System.exit(exit);
    }

    /** HTTP 경계 테스트만 baseUrl/합성 키를 주입한다. 사용자 명령에서는 공식 주소만 사용한다. */
    static int probe(String itemSeq, String baseUrl, String key, Path reportPath, PrintStream out) throws Exception {
        if (!validCode(itemSeq)) { out.println("officialEvidence=INVALID_ITEM_SEQ"); return 2; }
        if (key == null || key.isBlank()) { out.println("officialEvidence=SKIPPED_MISSING_KEY"); return 2; }
        var json = new JsonMapper();
        var client = new MeasuredClient(json,baseUrl,key);
        long start = System.nanoTime();
        Report report;
        try (var executor = new SimpleAsyncTaskExecutor("official-evidence-probe-")) {
            executor.setVirtualThreads(true);
            executor.setTaskTerminationTimeout(1000);
            var collector = new OfficialDrugEvidence(client,executor,4);
            var medication = new AnswerInput.Medication(itemSeq,"public item probe","","",1,null,null,null,List.of(),null);
            var evidence = collector.collect(List.of(medication),ChatDeadline.after(Duration.ofSeconds(40)));
            List<SourceResult> results = new ArrayList<>();
            for (var lookup : evidence.lookups()) {
                boolean easy = lookup.source().source() == AnswerSource.Type.EASY_DRUG;
                var call = client.calls.get(easy ? OfficialDrugEvidence.EASY_PATH : OfficialDrugEvidence.PERMIT_PATH);
                String status = lookup.failed() ? "FAILED" : lookup.source().ok() ? "FOUND"
                        : call != null && call.rows()==0 && Long.valueOf(0).equals(call.totalCount()) ? "NOT_FOUND" : "NO_CONTENT";
                results.add(new SourceResult(lookup.source().source().name(),status,
                        call==null?null:call.elapsedMs(),call==null?null:call.rows(),call==null?null:call.totalCount(),
                        call!=null&&call.exactItemMatched(),lookup.facts().size(),
                        lookup.facts().values().stream().mapToInt(String::length).sum(),
                        lookup.source().cancelled(),lookup.source().updatedAt(),
                        call==null?null:call.httpStatus(),call==null?null:call.providerCode()));
            }
            long failures=results.stream().filter(r->r.result().equals("FAILED")).count();
            String result=failures==results.size()?"FAILED":failures>0?"PARTIAL"
                    :evidence.hasOfficialInformation()?"READY":"NO_INFORMATION";
            report=new Report(OffsetDateTime.now(TimeZoneConfig.ZONE).toString(),itemSeq,result,
                    (System.nanoTime()-start)/1_000_000,List.copyOf(results));
        }
        // 명시한 필드만 직렬화한다. 공식 본문·제품명·요청 URL·키를 report 객체에 넣지 않는다.
        String serialized=json.writeValueAsString(report);
        Path parent=reportPath.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Files.writeString(reportPath,serialized+System.lineSeparator(),StandardCharsets.UTF_8);
        out.println(serialized);
        return report.sources().stream().anyMatch(r->r.result().equals("FAILED"))?1:0;
    }

    private static boolean validCode(String code) { return code!=null&&code.matches("[0-9]{9}"); }
    record Report(String observedAt, String itemSeq, String result, long elapsedMs, List<SourceResult> sources) {}
    record SourceResult(String source, String result, Long elapsedMs, Integer rows, Long totalCount,
                        boolean exactItemMatched, int factFields, int factCharacters, boolean cancelled,
                        LocalDate updatedAt, Integer httpStatus, String providerCode) {}
    private record Call(long elapsedMs, Integer rows, Long totalCount, boolean exactItemMatched,
                        Integer httpStatus, String providerCode) {}

    /** 같은 PublicDataClient의 호출을 재사용하고 숫자/불리언/허용 코드만 잠깐 보관한다. */
    private static final class MeasuredClient extends PublicDataClient {
        private final Map<String,Call> calls=new ConcurrentHashMap<>();
        MeasuredClient(JsonMapper json,String baseUrl,String key) { super(json,baseUrl,key,2000,5000,4,1000); }
        @Override public JsonNode get(String path,Map<String,?> params) {
            return measured(path, params, () -> super.get(path, params));
        }
        @Override public JsonNode get(String path,Map<String,?> params, java.time.Duration budget) {
            return measured(path, params, () -> super.get(path, params, budget));
        }
        private JsonNode measured(String path, Map<String,?> params, java.util.function.Supplier<JsonNode> call) {
            long start=System.nanoTime();
            try {
                var body=call.get();
                var items=items(body);
                boolean easy=path.equals(OfficialDrugEvidence.EASY_PATH);
                String code=String.valueOf(params.get(easy?"itemSeq":"item_seq"));
                boolean match=items.stream().anyMatch(item->code.equals(item.path(easy?"itemSeq":"ITEM_SEQ").asString("")));
                String count=body.path("totalCount").asString("");
                Long total=count.matches("[0-9]{1,18}")?Long.valueOf(count):null;
                calls.put(path,new Call((System.nanoTime()-start)/1_000_000,items.size(),total,match,null,null));
                return body;
            } catch (RuntimeException exception) {
                Integer status=null;String code=null;
                for(Throwable cause=exception;cause!=null;cause=cause.getCause()) {
                    if(cause instanceof RestClientResponseException http) {
                        status=http.getStatusCode().value();String body=http.getResponseBodyAsString();
                        code="unknown";
                        for(String known:List.of("SERVICE_KEY_IS_NOT_REGISTERED_ERROR","SERVICE_ACCESS_DENIED_ERROR",
                                "SERVICE_KEY_IS_NULL","INVALID_REQUEST_PARAMETER_ERROR","NO_OPENAPI_SERVICE_ERROR",
                                "PERMISSION_DENIED","LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR")) {
                            if(body.contains(known)) { code=known;break; }
                        }
                        break;
                    }
                }
                calls.put(path,new Call((System.nanoTime()-start)/1_000_000,null,null,false,status,code));
                throw exception;
            }
        }
    }
}
