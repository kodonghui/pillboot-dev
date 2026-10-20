package org.semiproject.pillbadeumserver.chat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.Points;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.semiproject.pillbadeumserver.chat.service.*;
import org.semiproject.pillbadeumserver.chat.domain.ChatMessage;
import org.semiproject.pillbadeumserver.chat.domain.ChatMessageRepository;
import org.semiproject.pillbadeumserver.chat.domain.QuestionCategory;
import org.semiproject.pillbadeumserver.common.ai.OpenAiClient;
import org.semiproject.pillbadeumserver.common.security.JwtTokenProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static io.qdrant.client.PointIdFactory.id;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 실제 MySQL 커밋·필터·임베딩 HTTP·Qdrant gRPC로 저장 순서와 실패 시 원문 보존을 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named="MYSQL_TEST_URL",matches=".+")
@EnabledIfEnvironmentVariable(named="QDRANT_TEST_PORT",matches=".+")
@Import(MessageEmbeddingMySqlTests.TrackingTasks.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class MessageEmbeddingMySqlTests {
    private static final JsonMapper JSON=new JsonMapper();
    private static final String MODEL="synthetic-embedding-model";
    private static final String PREFIX="embedding_test_"+UUID.randomUUID().toString().substring(0,8);
    private static final String QUESTION="synthetic-private-question";
    private static final String ANSWER_BODY="synthetic-private-answer";
    private static final String ANSWER=ANSWER_BODY+"\n@@SOURCES@@\n[]";
    private static final AtomicInteger activeTasks=new AtomicInteger();
    private static final AtomicInteger httpActive=new AtomicInteger();
    private static HttpServer server;
    private static ExecutorService httpThreads;
    private static volatile MessageEmbeddingMySqlTests current;
    @Autowired MockMvc mvc;
    @Autowired JwtTokenProvider tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired QdrantClient qdrant;
    @Autowired MessageVectorStore vectors;
    @Autowired QuestionStore store;
    @Autowired ChatAnswerService answers;
    @Autowired MessageEmbeddingService embeddings;
    @MockitoBean QuestionClassifier classifier;
    @MockitoBean AnswerGenerator generator;
    @MockitoBean DurCacheStore cache;
    @MockitoSpyBean OpenAiClient openAi;
    @MockitoSpyBean ChatMessageRepository messages;
    private final List<JsonNode> requests=new CopyOnWriteArrayList<>();
    private final List<Boolean> committedChecks=new CopyOnWriteArrayList<>();
    private final List<Boolean> workerChecks=new CopyOnWriteArrayList<>();
    private volatile int httpStatus=200;
    private volatile boolean malformedVector;
    private volatile CountDownLatch questionGate=new CountDownLatch(0);
    private volatile CountDownLatch answerGate=new CountDownLatch(0);
    private long memberId,profileId,sessionId;

    @DynamicPropertySource
    static void localOnly(DynamicPropertyRegistry registry){
        registry.add("qdrant.enabled",()->true);
        registry.add("qdrant.host",()->"127.0.0.1");
        registry.add("qdrant.port",()->System.getenv("QDRANT_TEST_PORT"));
        registry.add("qdrant.collection-prefix",()->PREFIX);
        registry.add("qdrant.vector-size",()->1536);
        registry.add("openai.base-url",MessageEmbeddingMySqlTests::baseUrl);
        registry.add("openai.api-key",()->"synthetic-embedding-test-key");
        registry.add("openai.model.embedding",()->MODEL);
    }

    static synchronized String baseUrl(){
        if(server==null)try{
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            httpThreads=Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(httpThreads);
            server.createContext("/embeddings",MessageEmbeddingMySqlTests::handle);server.start();
        }catch(Exception e){throw new IllegalStateException(e);}
        return "http://127.0.0.1:"+server.getAddress().getPort();
    }

    private static void handle(HttpExchange exchange){
        httpActive.incrementAndGet();
        try{
            var test=current;var request=JSON.readTree(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            test.requests.add(request);
            String input=request.path("input").asString();boolean answer=ANSWER_BODY.equals(input);
            test.committedChecks.add(test.jdbc.queryForObject("SELECT COUNT(*) FROM chat_message WHERE session_id=? AND role=? AND content=?",
                    Integer.class,test.sessionId,answer?"assistant":"user",answer?ANSWER:input)>0);
            (answer?test.answerGate:test.questionGate).await(5,TimeUnit.SECONDS);
            String body=test.httpStatus==200?JSON.writeValueAsString(java.util.Map.of("data",List.of(java.util.Map.of("embedding",
                    test.malformedVector?List.of(1.0):java.util.stream.IntStream.range(0,1536).mapToObj(i->i<3?0.25:0.0).toList())),
                    "usage",java.util.Map.of("prompt_tokens",7,"total_tokens",7))):"synthetic transient failure";
            exchange.getResponseHeaders().set("Content-Type","application/json");
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(test.httpStatus,bytes.length);exchange.getResponseBody().write(bytes);
        }catch(Exception ignored){
            // 취소된 HTTP 요청은 연결이 끊길 수 있다. 상태·최종 포인트는 테스트 스레드에서 검증한다.
        }finally{exchange.close();httpActive.decrementAndGet();}
    }

    @BeforeEach
    void prepareOnlyOwnedSyntheticData() throws Exception {
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("pill_test");
        try(var c=dataSource.getConnection()){ScriptUtils.executeSqlScript(c,new FileSystemResource("sql/01_PILLBADEUM_DB_SCRIPT.sql"));}
        memberId=insert("INSERT INTO member(email,password_hash,nickname,user_role,created_at,updated_at) VALUES(?,'fixture','embed','USER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)","embed-"+UUID.randomUUID()+"@example.test");
        profileId=insert("INSERT INTO profile(member_id,profile_name,relationship_type,profile_birth_year,profile_gender,profile_pregnant,created_at,updated_at) VALUES(?,'embed','SELF',1990,'FEMALE',FALSE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",memberId);
        sessionId=insert("INSERT INTO chat_session(member_id,profile_id,snap_birth_year,snap_gender,snap_pregnant,created_at,updated_at) VALUES(?,?,1990,'FEMALE',NULL,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",memberId,profileId);
        current=this;
        when(classifier.classify(anyString())).thenReturn(QuestionCategory.PASS);
        when(generator.generate(any(),any())).thenReturn(new GeneratedAnswer(ANSWER,"synthetic-response-id",null));
        doAnswer(inv->{workerChecks.add(Thread.currentThread().isVirtual()&&!TransactionSynchronizationManager.isActualTransactionActive());return inv.callRealMethod();})
                .when(openAi).embed(anyString());
    }

    @AfterEach
    void cleanupOnlyOwnedRowsAndCollection() throws Exception {
        questionGate.countDown();answerGate.countDown();
        await(()->activeTasks.get()==0&&httpActive.get()==0);
        if(exists())qdrant.deleteCollectionAsync(vectors.collectionName(MODEL)).get(3,TimeUnit.SECONDS);
        if(memberId>0){
            jdbc.update("DELETE FROM chat_message WHERE session_id=?",sessionId);
            jdbc.update("DELETE FROM chat_session WHERE member_id=?",memberId);
            jdbc.update("DELETE FROM profile WHERE member_id=?",memberId);
            jdbc.update("DELETE FROM member WHERE member_id=?",memberId);
        }
    }

    @AfterAll static void closeHttp(){if(server!=null)server.stop(0);if(httpThreads!=null)httpThreads.close();}

    @Test
    void bothMessagesCommitBeforeEmbeddingAndResponseDoesNotWaitForAnswerEmbedding() throws Exception {
        answerGate=new CountDownLatch(1);
        var result=send(200);
        long question=result.path("question").path("messageId").asLong(),answer=result.path("answer").path("messageId").asLong();
        await(()->requests.size()==2);
        assertThat(activeTasks.get()).isPositive();assertThat(countMessages()).isEqualTo(2);
        assertThat(countPoints()).isEqualTo(1);
        answerGate.countDown();await(()->activeTasks.get()==0&&httpActive.get()==0);
        assertThat(countPoints()).isEqualTo(2);
        var points=qdrant.retrieveAsync(vectors.collectionName(MODEL),List.of(id(pointId(question)),id(pointId(answer))),true,true,null).get(3,TimeUnit.SECONDS);
        assertThat(points).hasSize(2);
        assertThat(points.stream().map(p->p.getPayloadMap().get("source_id").getIntegerValue())).containsExactlyInAnyOrder(question,answer);
        for(var point:points){
            assertThat(point.getPayloadMap()).containsOnlyKeys("source_type","source_id","session_id","model");
            assertThat(point.getPayloadMap().get("session_id").getIntegerValue()).isEqualTo(sessionId);
            assertThat(point.getPayloadMap().get("source_type").getStringValue()).isEqualTo("MESSAGE");
            assertThat(point.getPayloadMap().get("model").getStringValue()).isEqualTo(MODEL);
        }
        assertThat(requests.stream().map(r->r.path("input").asString())).containsExactlyInAnyOrder(QUESTION,ANSWER_BODY);
        assertThat(requests).allSatisfy(r->{assertThat(r.path("model").asString()).isEqualTo(MODEL);assertThat(r.path("encoding_format").asString()).isEqualTo("float");assertThat(r.size()).isEqualTo(3);});
        assertThat(committedChecks).containsExactly(true,true);assertThat(workerChecks).containsExactly(true,true);
        var state=jdbc.queryForMap("SELECT * FROM chat_session WHERE session_id=?",sessionId);
        embeddings.questionCommitted(sessionId,question,QUESTION,ChatDeadline.after(Duration.ofSeconds(4)));
        assertThat(countPoints()).isEqualTo(2);
        assertThat(jdbc.queryForMap("SELECT * FROM chat_session WHERE session_id=?",sessionId)).isEqualTo(state);
    }

    @Test
    void blockedQuestionNeverCreatesMessagesOrEmbeddingRequests() throws Exception {
        when(classifier.classify(anyString())).thenReturn(QuestionCategory.OFF_TOPIC);
        var before=jdbc.queryForMap("SELECT * FROM chat_session WHERE session_id=?",sessionId);
        assertThat(send(200).path("result").asString()).isEqualTo("BLOCKED");
        assertThat(countMessages()).isZero();assertThat(requests).isEmpty();assertThat(exists()).isFalse();
        assertThat(jdbc.queryForMap("SELECT * FROM chat_session WHERE session_id=?",sessionId)).isEqualTo(before);
    }

    @Test
    void generatorFailureKeepsOnlyCommittedQuestionAndItsVector() throws Exception {
        doThrow(new IllegalStateException("synthetic generation failure")).when(generator).generate(any(),any());
        var error=send(503);
        assertThat(error.path("questionMessageId").asLong()).isPositive();assertThat(countMessages()).isEqualTo(1);
        assertThat(countPoints()).isEqualTo(1);assertThat(requests).hasSize(1);
    }

    @Test
    void transientEmbeddingFailuresRetryOnceAndKeepTheAnsweredResponse() throws Exception {
        httpStatus=503;
        assertThat(send(200).path("result").asString()).isEqualTo("ANSWERED");
        await(()->activeTasks.get()==0&&httpActive.get()==0);
        assertThat(requests).hasSize(4);assertThat(countMessages()).isEqualTo(2);assertThat(exists()).isFalse();
    }

    @Test
    void malformedDimensionIsNotStoredOrRetriedButAnswerStillCommits() throws Exception {
        malformedVector=true;
        assertThat(send(200).path("result").asString()).isEqualTo("ANSWERED");
        await(()->activeTasks.get()==0&&httpActive.get()==0);
        assertThat(requests).hasSize(2);assertThat(countMessages()).isEqualTo(2);assertThat(exists()).isFalse();
    }

    @Test
    void incompatibleExistingCollectionIsNotRecreatedAndDoesNotDiscardAnswer() throws Exception {
        qdrant.createCollectionAsync(vectors.collectionName(MODEL),Collections.VectorParams.newBuilder().setSize(1536).setDistance(Collections.Distance.Dot).build()).get(3,TimeUnit.SECONDS);
        assertThat(send(200).path("result").asString()).isEqualTo("ANSWERED");
        await(()->activeTasks.get()==0&&httpActive.get()==0);
        assertThat(countMessages()).isEqualTo(2);assertThat(countPoints()).isZero();
        var info=qdrant.getCollectionInfoAsync(vectors.collectionName(MODEL)).get(3,TimeUnit.SECONDS);
        assertThat(info.getConfig().getParams().getVectorsConfig().getParams().getDistance()).isEqualTo(Collections.Distance.Dot);
    }

    @Test
    void answerCommitRollbackDoesNotSubmitAnswerEmbedding() throws Exception {
        failCommitFor("assistant");
        assertThat(send(503).path("questionMessageId").asLong()).isPositive();
        await(()->activeTasks.get()==0&&httpActive.get()==0);
        assertThat(countMessages()).isEqualTo(1);assertThat(requests).hasSize(1);assertThat(countPoints()).isEqualTo(1);
    }

    @Test
    void questionCommitRollbackNeverStartsEmbeddingOrAnswerGeneration() throws Exception {
        failCommitFor("user");send(500);
        assertThat(countMessages()).isZero();assertThat(requests).isEmpty();assertThat(exists()).isFalse();
        verifyNoInteractions(generator);
    }

    @Test
    void expiredRequestCannotWriteVectorsAfterLateEmbeddingReturns() throws Exception {
        var saved=store.save(sessionId,memberId,profileId,QUESTION);questionGate=new CountDownLatch(1);
        assertThatThrownBy(()->answers.complete(sessionId,memberId,profileId,saved,ChatDeadline.after(Duration.ofMillis(150))))
                .isInstanceOf(AnswerFailedException.class);
        questionGate.countDown();await(()->activeTasks.get()==0&&httpActive.get()==0);
        assertThat(countMessages()).isEqualTo(1);assertThat(exists()).isFalse();verifyNoInteractions(generator);
    }

    private void failCommitFor(String role){
        var original=mockingDetails(messages).getMockCreationSettings().getDefaultAnswer();
        doAnswer(inv->{ChatMessage message=inv.getArgument(0);Object saved=original.answer(inv);
            if(role.equals(message.getRole()))TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                @Override public void beforeCommit(boolean readOnly){throw new DataIntegrityViolationException("synthetic commit rejection");}
            });return saved;
        }).when(messages).save(any());
    }
    private JsonNode send(int expected) throws Exception {
        var r=mvc.perform(post("/api/chat-sessions/{id}/messages",sessionId)
                .header("Authorization","Bearer "+tokens.createAccessToken(memberId,"fixture@example.test","ROLE_USER"))
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(java.util.Map.of("content",QUESTION))))
                .andExpect(status().is(expected)).andReturn();return JSON.readTree(r.getResponse().getContentAsString());
    }
    private int countMessages(){return jdbc.queryForObject("SELECT COUNT(*) FROM chat_message WHERE session_id=?",Integer.class,sessionId);}
    private boolean exists() throws Exception{return qdrant.collectionExistsAsync(vectors.collectionName(MODEL)).get(3,TimeUnit.SECONDS);}
    private long countPoints() throws Exception{return exists()?qdrant.countAsync(vectors.collectionName(MODEL)).get(3,TimeUnit.SECONDS):0;}
    private UUID pointId(long message){return UUID.nameUUIDFromBytes(("MESSAGE:"+sessionId+":"+message).getBytes(StandardCharsets.UTF_8));}
    private void await(BooleanSupplier ready) throws Exception {
        long until=System.nanoTime()+Duration.ofSeconds(6).toNanos();
        while(!ready.getAsBoolean()&&System.nanoTime()<until)Thread.sleep(10);
        assertThat(ready.getAsBoolean()).isTrue();
    }
    private long insert(String sql,Object...args){var key=new GeneratedKeyHolder();jdbc.update(c->{var s=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);return s;},key);return key.getKey().longValue();}

    @TestConfiguration
    static class TrackingTasks {
        @Bean(name="applicationTaskExecutor",destroyMethod="close")
        SimpleAsyncTaskExecutor tasks(){
            var executor=new SimpleAsyncTaskExecutor();executor.setVirtualThreads(true);
            executor.setTaskDecorator(task->{activeTasks.incrementAndGet();return ()->{try{task.run();}finally{activeTasks.decrementAndGet();}};});return executor;
        }
    }
}
