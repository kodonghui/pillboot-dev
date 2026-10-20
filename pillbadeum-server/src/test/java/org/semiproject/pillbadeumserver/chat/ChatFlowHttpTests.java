package org.semiproject.pillbadeumserver.chat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.qdrant.client.QdrantClient;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.semiproject.pillbadeumserver.chat.service.MessageVectorStore;
import org.semiproject.pillbadeumserver.chat.service.OfficialDrugEvidence;
import org.semiproject.pillbadeumserver.common.config.TimeZoneConfig;
import org.semiproject.pillbadeumserver.common.security.JwtTokenProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CHAT-001~009·COMMON-001~004: 실제 서버 포트/JWT·MySQL·Qdrant를 한 대화 흐름으로 검증한다.
 * 서비스 Bean을 대체하지 않고 외부 제공자만 로컬 HTTP로 바꾼다. 실제 키·유료 호출·사용자 DB는 사용하지 않는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "MYSQL_TEST_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "QDRANT_TEST_PORT", matches = ".+")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class ChatFlowHttpTests {
    private static final JsonMapper JSON = new JsonMapper();
    private static final String MODEL = "synthetic-flow-embedding";
    private static final String PREFIX = "http_flow_test_" + UUID.randomUUID().toString().substring(0, 8);
    private static final String AI_KEY = "synthetic-flow-ai-key";
    private static final String PUBLIC_KEY = "synthetic-flow-public-key";
    private static final AtomicInteger activeHttp = new AtomicInteger();
    private static HttpServer provider;
    private static ExecutorService providerThreads;
    private static volatile ChatFlowHttpTests current;

    @Value("${local.server.port}") int port;
    @Autowired JwtTokenProvider tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired QdrantClient qdrant;
    @Autowired MessageVectorStore vectors;
    @Autowired PasswordEncoder passwords;
    private HttpClient browser;
    private final List<Long> members = new ArrayList<>();
    private final List<JsonNode> answerRequests = new CopyOnWriteArrayList<>();
    private final List<JsonNode> summaryRequests = new CopyOnWriteArrayList<>();
    private final List<String> embeddingInputs = new CopyOnWriteArrayList<>();
    private final AtomicInteger decisions = new AtomicInteger();
    private volatile int answerStatus = 200;
    private volatile int publicStatus = 200;
    private volatile String answerText;
    private volatile String blockedQuestion;
    private CountDownLatch answerEntered = new CountDownLatch(0);
    private CountDownLatch answerRelease = new CountDownLatch(0);
    private long memberId, profileId, secondProfileId, foreignProfileId;
    private String token, drugCode;

    @DynamicPropertySource
    static void onlyLocalProviders(DynamicPropertyRegistry registry) {
        registry.add("openai.base-url", ChatFlowHttpTests::providerUrl);
        registry.add("openai.api-key", () -> AI_KEY);
        registry.add("openai.model.chat", () -> "synthetic-flow-chat");
        registry.add("openai.model.decision", () -> "synthetic-flow-decision");
        registry.add("openai.model.embedding", () -> MODEL);
        registry.add("openai.reasoning-effort", () -> "");
        registry.add("public-data.base-url", ChatFlowHttpTests::providerUrl);
        registry.add("public-data.service-key", () -> PUBLIC_KEY);
        registry.add("qdrant.enabled", () -> true);
        registry.add("qdrant.host", () -> "127.0.0.1");
        registry.add("qdrant.port", () -> System.getenv("QDRANT_TEST_PORT"));
        registry.add("qdrant.collection-prefix", () -> PREFIX);
        registry.add("qdrant.vector-size", () -> 1536);
    }

    private static synchronized String providerUrl() {
        if (provider == null) try {
            provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            providerThreads = Executors.newVirtualThreadPerTaskExecutor();
            provider.setExecutor(providerThreads);
            provider.createContext("/", ChatFlowHttpTests::handleProvider);
            provider.start();
        } catch (IOException exception) { throw new IllegalStateException(exception); }
        return "http://127.0.0.1:" + provider.getAddress().getPort();
    }

    @BeforeEach
    void prepareSyntheticOwnersAndMedication() throws Exception {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("pill_test");
        try (var connection = dataSource.getConnection()) {
            org.semiproject.pillbadeumserver.TestDatabaseSchema.prepare(connection);
        }
        browser = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .version(HttpClient.Version.HTTP_1_1).build();
        memberId = member();
        profileId = profile(memberId);
        secondProfileId = profile(memberId);
        foreignProfileId = profile(member());
        token = tokens.createAccessToken(memberId, "flow@example.test", "ROLE_USER");
        drugCode = "flow-" + UUID.randomUUID();
        jdbc.update("INSERT INTO drug(item_seq,name,maker,etc_otc,class_name,shape,color,form,material,synced_at) "
                + "VALUES(?,'합성등록약정','fixture','OTC','fixture','fixture','fixture','TABLET','fixture',CURRENT_TIMESTAMP)", drugCode);
        for (long profile : List.of(profileId, secondProfileId)) {
            jdbc.update("INSERT INTO my_drug(profile_id,item_seq,dose_per_time,timing,start_date,end_date,dose_times,source) "
                    + "VALUES(?,?,1,'fixture',?,?,'08:00','SEARCH')", profile, drugCode,
                    LocalDate.now(TimeZoneConfig.ZONE), LocalDate.now(TimeZoneConfig.ZONE).plusDays(1));
        }
        current = this;
    }

    /** 방 생성부터 41턴/백그라운드 요약/검색/페이지 조회까지 실제 HTTP 계약을 연결한다. */
    @Test
    void fortyOneHttpTurnsProduceFourSummariesAndRetrieveEarlierContext(CapturedOutput output) throws Exception {
        call("POST", rooms(profileId), null, null, 401);
        call("POST", rooms(foreignProfileId), null, token, 403);
        long session = create(profileId, 201);
        assertThat(create(profileId, 200)).isEqualTo(session);
        long separate = create(secondProfileId, 201);
        assertThat(separate).isNotEqualTo(session);

        for (int turn = 1; turn <= 41; turn++) {
            JsonNode result = question(session, "합성 질문 " + turn, 200);
            assertThat(result.path("result").asString()).isEqualTo("ANSWERED");
            assertThat(result.path("question").path("turnNo").asInt()).isEqualTo(turn);
            assertThat(result.path("answer").path("turnNo").asInt()).isEqualTo(turn);
            assertThat(result.path("answer").path("createdAt").asString()).endsWith("+09:00");
            if (turn % 10 == 0) {
                final int completedWindows = turn / 10;
                await(() -> summaryCount(session) == completedWindows && pointCount() == completedWindows * 21L);
            }
        }
        await(() -> pointCount() == 86);
        assertThat(decisions.get()).isEqualTo(41);
        assertThat(answerRequests).hasSize(41);
        assertThat(summaryRequests).hasSize(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_message WHERE session_id=?", Long.class, session)).isEqualTo(82);
        assertThat(jdbc.queryForList("SELECT from_turn FROM chat_summary WHERE session_id=? ORDER BY from_turn", Integer.class, session))
                .containsExactly(1, 11, 21, 31);
        assertThat(jdbc.queryForList("SELECT to_turn FROM chat_summary WHERE session_id=? ORDER BY from_turn", Integer.class, session))
                .containsExactly(10, 20, 30, 40);
        for (var request : summaryRequests) {
            var window = JSON.readTree(request.path("input").path(0).path("content").asString());
            assertThat(window.path("turns").size()).isEqualTo(10);
            for (var turn : window.path("turns")) {
                assertThat(turn.path("answer").asString()).doesNotContain("@@SOURCES@@");
                assertThat(turn.path("question").asString()).startsWith("합성 질문 ");
            }
        }
        // 41번째 실제 모델 입력에서 오래된 검색 요약 1개→최근 3요약→완성 6턴→현재 질문을 확인한다.
        var input = answerRequests.getLast().path("input");
        assertThat(input.size()).isEqualTo(18);
        for (int i = 0; i < 4; i++) {
            assertThat(input.path(i + 1).path("content").asString()).contains("구간 요약 " + (i * 10 + 1) + "-" + (i * 10 + 10));
        }
        assertThat(input.path(5).path("content").asString()).isEqualTo("합성 질문 35");
        assertThat(input.path(16).path("role").asString()).isEqualTo("assistant");
        assertThat(input.path(17).path("content").asString()).isEqualTo("합성 질문 41");

        JsonNode latest = history(session, "", 200);
        assertThat(latest.path("messages").size()).isEqualTo(60);
        assertThat(latest.path("messages").path(0).path("turnNo").asInt()).isEqualTo(12);
        assertThat(latest.path("hasMore").asBoolean()).isTrue();
        assertThat(latest.path("retryMessageId").isNull()).isTrue();
        JsonNode earlier = history(session, "?beforeTurn=12", 200);
        assertThat(earlier.path("messages").size()).isEqualTo(22);
        assertThat(earlier.path("hasMore").asBoolean()).isFalse();
        assertThat(earlier.path("messages").path(0).path("turnNo").asInt()).isEqualTo(1);
        assertThat(latest.path("messages").path(1).path("content").asString()).contains("@@SOURCES@@");
        assertThat(history(separate, "", 200).path("messages").size()).isZero();
        assertThat(call("GET", rooms(profileId), null, token, 200).path("sessions").size()).isEqualTo(1);
        call("GET", messages(session), null, tokens.createAccessToken(members.getLast(), "other@example.test", "ROLE_USER"), 403);
        assertPrivateValuesNotLogged(output);
    }

    /** 생성 실패의 질문 ID를 HTTP retry에 넘겨 재분류·USER 저장·질문 임베딩 중복을 확인한다. */
    @Test
    void failedAnswerIsRecoveredThroughHttpWithoutNewQuestionOrEmbedding(CapturedOutput output) throws Exception {
        long session = create(profileId, 201);
        answerStatus = 503;
        JsonNode failure = question(session, "복구할 합성 질문", 503);
        long questionId = failure.path("questionMessageId").asLong();
        assertThat(questionId).isPositive();
        assertThat(history(session, "", 200).path("retryMessageId").asLong()).isEqualTo(questionId);
        assertThat(history(session, "", 200).path("messages").size()).isEqualTo(1);
        assertThat(pointCount()).isEqualTo(1);
        answerStatus = 200;
        JsonNode recovered = call("POST", messages(session) + "/" + questionId + "/retry", null, token, 200);
        assertThat(recovered.path("question").path("messageId").asLong()).isEqualTo(questionId);
        assertThat(recovered.path("answer").path("turnNo").asInt()).isEqualTo(1);
        await(() -> pointCount() == 2);
        assertThat(decisions.get()).isEqualTo(1);
        assertThat(embeddingInputs.stream().filter("복구할 합성 질문"::equals).count()).isEqualTo(1);
        assertThat(history(session, "", 200).path("retryMessageId").isNull()).isTrue();
        JsonNode alreadyAnswered = call("POST", messages(session) + "/" + questionId + "/retry", null, token, 400);
        assertThat(alreadyAnswered.path("code").asString()).isEqualTo("RETRY_NOT_ALLOWED");
        assertThat(history(session, "", 200).path("messages").size()).isEqualTo(2);
        assertPrivateValuesNotLogged(output);
    }

    /** 실제 로그인/BCrypt/발급 토큰에서 시작해 DB의 중간 길이 한글6턴이 모델 입력까지 전달되는지 확인한다. */
    @Test
    void issuedLoginTokenConnectsToOwnedChatAndSixKoreanTurns(CapturedOutput output) throws Exception {
        String email = jdbc.queryForObject("SELECT email FROM member WHERE member_id=?", String.class, memberId);
        String password = "synthetic-login-only-2026!";
        jdbc.update("UPDATE member SET password_hash=?,user_role='ROLE_USER' WHERE member_id=?", passwords.encode(password), memberId);
        assertThat(call("POST", "/api/auth/login", Map.of("email", email, "password", "incorrect"), null, 401)
                .path("code").asString()).isEqualTo("LOGIN_FAILED");
        var loggedIn = call("POST", "/api/auth/login", Map.of("email", email, "password", password), null, 200);
        token = loggedIn.path("accessToken").asString();
        assertThat(token).isNotBlank();
        assertThat(loggedIn.path("member").path("memberId").asLong()).isEqualTo(memberId);
        assertThat(call("GET", "/api/auth/me", null, token, 200).path("memberId").asLong()).isEqualTo(memberId);
        assertThat(call("GET", rooms(profileId), null, "invalid-token", 401).path("code").asString())
                .isEqualTo("UNAUTHENTICATED");
        call("GET", rooms(foreignProfileId), null, token, 403);
        call("GET", rooms(Long.MAX_VALUE), null, token, 404);
        long session = create(profileId, 201);
        answerText = "합성 답변".repeat(60); // 서버가 붙이는 DUR 안내까지 합쳐6턴이 예산에 드는 합성 응답이다.
        for (int turn = 1; turn <= 7; turn++) question(session, "합성 질문".repeat(20) + turn, 200);
        await(() -> pointCount() == 14);
        var input = answerRequests.getLast().path("input");
        assertThat(input.size()).isEqualTo(14); // 배경 + DB에서 읽은 완성6턴 + 현재 질문이다.
        for (int i = 0; i < 6; i++) {
            assertThat(input.path(1 + i * 2).path("content").asString()).isEqualTo("합성 질문".repeat(20) + (i + 1));
            assertThat(input.path(2 + i * 2).path("content").asString()).startsWith(answerText).doesNotContain("@@SOURCES@@");
        }
        assertThat(history(session, "", 200).path("messages").size()).isEqualTo(14);
        assertThat(jdbc.queryForObject("SELECT COUNT(token_count) FROM chat_message WHERE session_id=?", Long.class, session)).isZero();
        assertPrivateValuesNotLogged(output);
        assertThat(output.getAll()).doesNotContain(password, email);
    }

    /** 공식/DUR 모두400이어도 모델 추측을 저장하지 않고 조회 실패 안내와 서버 근거를 보존한다. */
    @Test
    void publicProviderFailuresKeepStoredAnswersExplicitAndNeverCallTheModel(CapturedOutput output) throws Exception {
        long session = create(profileId, 201);
        publicStatus = 400;
        var first = question(session, "등록 약의 공식 정보는?", 200);
        assertThat(first.path("answer").path("durNotice").isNull()).isTrue();
        var second = question(session, "이 약의 금기는?", 200);
        var answer = second.path("answer");
        assertThat(answer.path("content").asString()).contains("지금은 공식 정보를 확인하지 못해", "금기가 없다는 뜻은 아니니");
        assertThat(answer.path("durNotice").path("lookup").asString()).isEqualTo("FAILED");
        assertThat(answer.path("durNotice").path("result").isNull()).isTrue();
        var sources = JSON.readTree(answer.path("content").asString().split("@@SOURCES@@", 2)[1].strip());
        assertThat(sources.size()).isEqualTo(4);
        for (var source : sources) if (!source.path("source").asString().equals("USER_INPUT"))
            assertThat(source.path("ok").asBoolean()).isFalse();
        assertThat(answerRequests).isEmpty();
        await(() -> pointCount() == 4);
        assertThat(history(session, "", 200).path("messages").size()).isEqualTo(4);
        assertThat(history(session, "", 200).path("retryMessageId").isNull()).isTrue();
        assertPrivateValuesNotLogged(output);
    }

    /** 응답을 래치로 잠근 동안 같은 방의 요청/표시와 다른 프로필의 진행을 함께 확인한다. */
    @Test
    void realConcurrentRequestsShareOneRoomGuardWhileAnotherProfileKeepsWorking() throws Exception {
        long session = create(profileId, 201), separate = create(secondProfileId, 201);
        blockedQuestion = "진행 중 합성 질문";
        answerEntered = new CountDownLatch(1);
        answerRelease = new CountDownLatch(1);
        var pending = browser.sendAsync(request("POST", messages(session), Map.of("content", blockedQuestion), token),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        try {
            assertThat(answerEntered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(history(session, "", 200).path("retryMessageId").isNull()).isTrue();
            assertThat(question(session, "겹친 합성 질문", 409).path("code").asString()).isEqualTo("REQUEST_IN_PROGRESS");
            long questionId = jdbc.queryForObject("SELECT message_id FROM chat_message WHERE session_id=? AND role='user'", Long.class, session);
            assertThat(call("POST", messages(session) + "/" + questionId + "/retry", null, token, 409).path("code").asString())
                    .isEqualTo("REQUEST_IN_PROGRESS");
            assertThat(question(separate, "다른 프로필 합성 질문", 200).path("result").asString()).isEqualTo("ANSWERED");
        } finally { answerRelease.countDown(); }
        assertThat(pending.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        assertThat(question(session, "다음 합성 질문", 200).path("question").path("turnNo").asInt()).isEqualTo(2);
        await(() -> pointCount() == 6);
        assertThat(decisions.get()).isEqualTo(3);
        assertThat(history(session, "", 200).path("messages").size()).isEqualTo(4);
        assertThat(history(separate, "", 200).path("messages").size()).isEqualTo(2);
    }

    /** 합성 회원의 행과 이 클래스의 모델 컬렉션만 삭제하며 늦은 HTTP 작업을 먼저 끝낸다. */
    @AfterEach
    void cleanOwnedData() throws Exception {
        answerRelease.countDown();
        await(() -> activeHttp.get() == 0);
        for (long member : members) {
            jdbc.update("DELETE x FROM chat_summary x JOIN chat_session s ON s.session_id=x.session_id WHERE s.member_id=?", member);
            jdbc.update("DELETE x FROM chat_message x JOIN chat_session s ON s.session_id=x.session_id WHERE s.member_id=?", member);
            jdbc.update("DELETE FROM chat_session WHERE member_id=?", member);
            jdbc.update("DELETE x FROM my_drug x JOIN profile p ON p.profile_id=x.profile_id WHERE p.member_id=?", member);
            jdbc.update("DELETE FROM profile WHERE member_id=?", member);
            jdbc.update("DELETE FROM member WHERE member_id=?", member);
        }
        jdbc.update("DELETE FROM dur_warning WHERE item_seq=?", drugCode);
        jdbc.update("DELETE FROM dur_interaction WHERE item_seq2=?", drugCode);
        jdbc.update("DELETE FROM drug WHERE item_seq=?", drugCode);
        if (qdrant.collectionExistsAsync(vectors.collectionName(MODEL)).get(3, TimeUnit.SECONDS)) {
            qdrant.deleteCollectionAsync(vectors.collectionName(MODEL)).get(3, TimeUnit.SECONDS);
        }
        browser.close();
    }

    @AfterAll
    static void closeProvider() {
        if (provider != null) provider.stop(0);
        if (providerThreads != null) providerThreads.close();
    }

    private long create(long profile, int status) throws Exception { return call("POST", rooms(profile), null, token, status).path("sessionId").asLong(); }
    private JsonNode question(long session, String text, int status) throws Exception { return call("POST", messages(session), Map.of("content", text), token, status); }
    private JsonNode history(long session, String query, int status) throws Exception { return call("GET", messages(session) + query, null, token, status); }
    private static String rooms(long profile) { return "/api/profiles/" + profile + "/chat-sessions"; }
    private static String messages(long session) { return "/api/chat-sessions/" + session + "/messages"; }

    private HttpRequest request(String method, String path, Map<String, ?> body, String auth) {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20));
        if (auth != null) builder.header("Authorization", "Bearer " + auth);
        if (body != null) builder.header("Content-Type", "application/json");
        return builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8)).build();
    }

    /** 합성 토큰/본문으로 실제 포트에 요청하고 상태·추적 헤더를 확인한 JSON 응답을 반환한다. */
    private JsonNode call(String method, String path, Map<String, ?> body, String auth, int status) throws Exception {
        var response = browser.send(request(method, path, body, auth), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).as("%s %s response %s", method, path, response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("X-Request-ID")).isPresent();
        return JSON.readTree(response.body());
    }

    private long summaryCount(long session) { return jdbc.queryForObject("SELECT COUNT(*) FROM chat_summary WHERE session_id=?", Long.class, session); }
    private long pointCount() {
        try {
            if (!qdrant.collectionExistsAsync(vectors.collectionName(MODEL)).get(3, TimeUnit.SECONDS)) return 0;
            return qdrant.countAsync(vectors.collectionName(MODEL)).get(3, TimeUnit.SECONDS);
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private long member() {
        long id = insert("INSERT INTO member(email,password_hash,nickname,user_role,created_at,updated_at) "
                + "VALUES(?,'fixture','비공개표식','USER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                "flow-" + UUID.randomUUID() + "@example.test");
        members.add(id); return id;
    }
    private long profile(long member) {
        return insert("INSERT INTO profile(member_id,profile_name,relationship_type,profile_birth_year,profile_gender,profile_pregnant,created_at,updated_at) "
                + "VALUES(?,'비공개표식','SELF',1990,'FEMALE',FALSE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", member);
    }
    private long insert(String sql, Object... args) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, key);
        return key.getKey().longValue();
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(20);
        assertThat(condition.getAsBoolean()).as("비동기 커밋/벡터/HTTP 후처리 완료").isTrue();
    }
    private void assertPrivateValuesNotLogged(CapturedOutput output) {
        assertThat(output.getAll()).doesNotContain(AI_KEY, PUBLIC_KEY, "비공개표식", token);
    }

    /** 로컬 제공자만 합성 응답을 반환하며 실제 제품 코드/키/모델 주소로 요청을 전달하지 않는다. */
    private static void handleProvider(HttpExchange exchange) throws IOException {
        activeHttp.incrementAndGet();
        try {
            var test = current;
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/1471000/") && test.publicStatus != 200) {
                respond(exchange, test.publicStatus, Map.of("error", "INVALID_REQUEST_PARAMETER_ERROR"));
                return;
            }
            if (path.equals("/decisions")) {
                test.decisions.incrementAndGet();
                respond(exchange, 200, Map.of("answers", List.of(Map.of("type", "choice", "name", "medication_category", "choice", "PASS"))));
            } else if (path.equals("/embeddings")) {
                var input = JSON.readTree(exchange.getRequestBody().readAllBytes());
                test.embeddingInputs.add(input.path("input").asString());
                respond(exchange, 200, Map.of("data", List.of(Map.of("embedding", IntStream.range(0, 1536).mapToObj(i -> i == 0 ? 1.0 : 0.0).toList())),
                        "usage", Map.of("prompt_tokens", 7, "total_tokens", 7)));
            } else if (path.equals("/responses")) {
                var input = JSON.readTree(exchange.getRequestBody().readAllBytes());
                boolean summary = input.path("instructions").asString().startsWith("필받음 대화 기록");
                String text;
                int status = 200;
                if (summary) {
                    test.summaryRequests.add(input);
                    var window = JSON.readTree(input.path("input").path(0).path("content").asString());
                    text = "구간 요약 " + window.path("fromTurn").asInt() + "-" + window.path("toTurn").asInt();
                } else {
                    test.answerRequests.add(input);
                    var messages = input.path("input");
                    String question = messages.path(messages.size() - 1).path("content").asString();
                    if (question.equals(test.blockedQuestion)) {
                        test.answerEntered.countDown();
                        if (!test.answerRelease.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("fixture gate timeout");
                    }
                    text = test.answerText == null ? "합성 응답: " + question : test.answerText;
                    status = test.answerStatus;
                }
                respond(exchange, status, status == 200 ? Map.of("id", "resp-flow", "status", "completed", "usage", Map.of("input_tokens", 30, "output_tokens", 10),
                        "output", List.of(Map.of("type", "message", "content", List.of(Map.of("type", "output_text", "text", text)))))
                        : Map.of("error", Map.of("code", "synthetic_failure")));
            } else if (path.equals(OfficialDrugEvidence.EASY_PATH) || path.equals(OfficialDrugEvidence.PERMIT_PATH)) {
                boolean easy = path.equals(OfficialDrugEvidence.EASY_PATH);
                String code = java.util.Arrays.stream(exchange.getRequestURI().getRawQuery().split("&"))
                        .filter(part -> part.startsWith(easy ? "itemSeq=" : "item_seq="))
                        .map(part -> URLDecoder.decode(part.substring(part.indexOf('=') + 1), StandardCharsets.UTF_8)).findFirst().orElseThrow();
                var item = easy ? Map.of("itemSeq", code, "efcyQesitm", "합성 공식 정보", "updateDe", "2026-10-20")
                        : Map.of("ITEM_SEQ", code, "EE_DOC_DATA", "<DOC>합성 허가 정보</DOC>", "CHANGE_DATE", "20261020");
                respond(exchange, 200, Map.of("header", Map.of("resultCode", "00"), "body", Map.of("totalCount", 1, "items", List.of(item))));
            } else if (path.startsWith("/1471000/DURPrdlstInfoService03/")) {
                respond(exchange, 200, Map.of("header", Map.of("resultCode", "00"), "body", Map.of("totalCount", 0, "items", List.of())));
            } else respond(exchange, 404, Map.of());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
            activeHttp.decrementAndGet();
        }
    }

    private static void respond(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
