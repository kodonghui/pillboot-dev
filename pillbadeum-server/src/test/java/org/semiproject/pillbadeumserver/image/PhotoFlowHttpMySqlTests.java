package org.semiproject.pillbadeumserver.image;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Statement;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.semiproject.pillbadeumserver.TestDatabaseSchema;
import org.semiproject.pillbadeumserver.common.config.TimeZoneConfig;
import org.semiproject.pillbadeumserver.common.file.FileStorage;
import org.semiproject.pillbadeumserver.common.security.JwtTokenProvider;
import org.semiproject.pillbadeumserver.drug.service.DrugSyncService;
import org.semiproject.pillbadeumserver.image.service.PhotoLifecycleService;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** REG-002~007: 실제 로그인·JWT·HTTP·MySQL·파일을 연결하고 외부 제공자만 로컬 HTTP로 바꾼다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "MYSQL_TEST_URL", matches = ".+")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PhotoFlowHttpMySqlTests {
  static final JsonMapper JSON = new JsonMapper();
  static final Path UPLOAD = tempDirectory();
  static final AtomicInteger ACTIVE = new AtomicInteger();
  static HttpServer provider;
  static ExecutorService workers;
  static volatile PhotoFlowHttpMySqlTests current;

  @Value("${local.server.port}")
  int port;

  @Autowired JdbcTemplate jdbc;
  @Autowired DataSource dataSource;
  @Autowired JwtTokenProvider tokens;
  @Autowired PasswordEncoder passwords;
  @Autowired PhotoLifecycleService lifecycle;
  @Autowired FileStorage files;
  @Autowired org.semiproject.pillbadeumserver.chat.service.RegistrationDurPrefetch durPrefetch;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
  HttpClient browser;
  long memberId, profileId, otherProfileId, foreignMember, foreignProfile;
  String token, foreignToken, code1, code2;
  final List<JsonNode> visionRequests = new CopyOnWriteArrayList<>();
  final List<String> lookups = new CopyOnWriteArrayList<>();
  volatile Object vision;
  volatile int visionStatus = 200;
  volatile List<Map<String, Object>> officialRows;
  volatile boolean sawUploaded;
  volatile boolean grainEmpty, permitMetadata;
  volatile CountDownLatch entered = new CountDownLatch(0), release = new CountDownLatch(0);

  @DynamicPropertySource
  static void providers(DynamicPropertyRegistry r) {
    r.add("openai.base-url", PhotoFlowHttpMySqlTests::providerUrl);
    r.add("openai.api-key", () -> "synthetic-photo-ai-key");
    r.add("openai.model.vision", () -> "synthetic-photo-vision");
    r.add("openai.model.chat", () -> "synthetic-photo-chat");
    r.add("openai.model.decision", () -> "synthetic-photo-decision");
    r.add("openai.reasoning-effort", () -> "");
    r.add("public-data.base-url", PhotoFlowHttpMySqlTests::providerUrl);
    r.add("public-data.service-key", () -> "synthetic-photo-public-key");
    r.add("file.upload.mac.path", () -> UPLOAD.toString());
    r.add("file.upload.windows.path", () -> UPLOAD.toString());
    r.add("qdrant.enabled", () -> false);
  }

  static synchronized String providerUrl() {
    if (provider == null)
      try {
        provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workers = Executors.newVirtualThreadPerTaskExecutor();
        provider.setExecutor(workers);
        provider.createContext("/", PhotoFlowHttpMySqlTests::handle);
        provider.start();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    return "http://127.0.0.1:" + provider.getAddress().getPort();
  }

  @BeforeEach
  void fixtures() throws Exception {
    try (var c = dataSource.getConnection()) {
      TestDatabaseSchema.prepare(c);
    }
    browser = HttpClient.newHttpClient();
    String email = "photo-" + UUID.randomUUID() + "@example.test";
    memberId = member(email);
    foreignMember = member("foreign-" + email);
    profileId = profile(memberId);
    otherProfileId = profile(memberId);
    foreignProfile = profile(foreignMember);
    token =
        json(
                "POST",
                "/api/auth/login",
                Map.of("email", email, "password", "fixture-pass-123"),
                null,
                200)
            .path("accessToken")
            .asString();
    assertThat(token).isNotBlank();
    foreignToken = tokens.createAccessToken(foreignMember, "foreign@example.test", "ROLE_USER");
    code1 = "9" + String.format("%09d", ThreadLocalRandom.current().nextInt(1, 999_999_998));
    code2 = code1 + "1";
    officialRows = List.of(row(code1, "합성약정", "A", "B"), row(code2, "합성약정", "X", "Y"));
    vision = prescription("합성약정");
    current = this;
  }

  @Test
  void prescriptionReloadSelectionAndReplayUseOfficialCodesAndCleanOnlyAfterCommit()
      throws Exception {
    JsonNode result = identify("PRESCRIPTION", token, 200, "image");
    assertThat(sawUploaded).isTrue();
    long id = result.path("photoId").asLong();
    assertThat(result.path("drugs").path(0).path("candidates").size()).isEqualTo(2);
    assertThat(lookups).anyMatch(s -> s.startsWith(DrugSyncService.PERMIT_INFO_PATH));
    var reloaded = json("GET", "/api/photos/" + id + "/candidates", null, token, 200);
    assertThat(reloaded.path("drugs")).isEqualTo(result.path("drugs"));
    var image = raw("GET", "/api/photos/" + id + "/image", null, token);
    assertThat(image.statusCode()).isEqualTo(200);
    assertThat(image.headers().firstValue("Cache-Control")).contains("no-store");
    var body = selection(id, "PRESCRIPTION_PHOTO", code1, code1);
    var saved = json("POST", drugs(profileId), body, token, 201);
    assertThat(saved.path("saved").size()).isEqualTo(1);
    assertThat(saved.path("skipped").size()).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM my_drug WHERE profile_id=?", Integer.class, profileId))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM my_drug WHERE profile_id=? AND start_date IS NULL AND"
                    + " dose_times IS NULL",
                Integer.class,
                profileId))
        .isEqualTo(1);
    assertNoPhotos();
    var repeated = json("POST", drugs(profileId), body, token, 200);
    assertThat(repeated.path("skipped").path(0).path("reason").asString())
        .isEqualTo("ALREADY_REGISTERED");
    assertThat(visionRequests).hasSize(1);
    assertThat(visionRequests.getFirst().toString())
        .contains("data:image/jpeg;base64,")
        .doesNotContain("patient-original.png");
  }

  @Test
  void foreignOwnersAndMismatchedProfileSourceOrCandidateCannotRegister() throws Exception {
    long id = identify("PRESCRIPTION", token, 200, "image").path("photoId").asLong();
    json("GET", "/api/photos/" + id + "/candidates", null, foreignToken, 404);
    assertThat(raw("GET", "/api/photos/" + id + "/image", null, foreignToken).statusCode())
        .isEqualTo(404);
    json("DELETE", "/api/photos/" + id, null, foreignToken, 404);
    json("POST", drugs(foreignProfile), selection(id, "PRESCRIPTION_PHOTO", code1), token, 403);
    json("POST", drugs(otherProfileId), selection(id, "PRESCRIPTION_PHOTO", code1), token, 400);
    json("POST", drugs(profileId), selection(id, "PILL_PHOTO", code1), token, 400);
    json(
        "POST",
        drugs(profileId),
        selection(id, "PRESCRIPTION_PHOTO", "1111111111111111111"),
        token,
        400);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM my_drug WHERE profile_id=?", Integer.class, profileId))
        .isZero();
    json("GET", "/api/photos/" + id + "/candidates", null, token, 200);
  }

  @Test
  void pillFrontBackAreLinkedAndSwappedPrintsRankTogetherThenBackDeleteRemovesBoth()
      throws Exception {
    vision = pill("합성약정", "B", "A");
    var result = identify("PILL", token, 200, "front", "back");
    long id = result.path("photoId").asLong(), back = result.path("photoIds").path(1).asLong();
    assertThat(result.path("candidates").path(0).path("itemSeq").asString()).isEqualTo(code1);
    assertThat(result.path("candidates").path(0).path("printMatched").asBoolean()).isTrue();
    var candidate = result.path("candidates").path(0);
    assertThat(candidate.path("printFront").asString()).isEqualTo("A");
    assertThat(candidate.path("printBack").asString()).isEqualTo("B");
    assertThat(candidate.path("shape").asString()).isEqualTo("원형");
    assertThat(candidate.path("color").asString()).isEqualTo("흰색");
    assertThat(
            json("GET", "/api/photos/" + id + "/candidates", null, token, 200).path("candidates"))
        .isEqualTo(result.path("candidates"));
    assertThat(
            jdbc.queryForObject(
                "SELECT front_photo_id FROM photo WHERE photo_id=?", Long.class, back))
        .isEqualTo(id);
    assertThat(raw("GET", "/api/photos/" + back + "/image", null, token).statusCode())
        .isEqualTo(200);
    json("DELETE", "/api/photos/" + back, null, token, 204);
    assertNoPhotos();
  }

  @Test
  void pillRegistrationUsesBothPhotosAndLeavesScheduleUnset() throws Exception {
    vision = pill("합성약정", "A", "B");
    long id = identify("PILL", token, 200, "front", "back").path("photoId").asLong();
    json("POST", drugs(profileId), selection(id, "PILL_PHOTO", code1), token, 201);
    assertNoPhotos();
  }

  @Test
  void imprintOnlyIsUnreadableAndDoesNotSearchWholeCatalog() throws Exception {
    vision = pill("", "A", "B");
    var result = identify("PILL", token, 200, "front", "back");
    assertThat(result.path("status").asString()).isEqualTo("UNREADABLE");
    assertThat(result.path("photoId").isNull()).isTrue();
    assertThat(lookups).isEmpty();
    assertNoPhotos();
  }

  @Test
  void onePillCannotRegisterTwoAlternativeCandidatesAndKeepsConfirmation() throws Exception {
    vision = pill("합성약정", "A", "B");
    long id = identify("PILL", token, 200, "front", "back").path("photoId").asLong();
    json("POST", drugs(profileId), selection(id, "PILL_PHOTO", code1, code2), token, 400);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM my_drug WHERE profile_id=?", Integer.class, profileId)).isZero();
    json("GET", "/api/photos/" + id + "/candidates", null, token, 200);
    json("POST", drugs(profileId), selection(id, "PILL_PHOTO", code1), token, 201);
  }

  @Test
  void onePrescriptionCardCannotRegisterTwoAlternativesButOverlappingCardsCan() throws Exception {
    long id = identify("PRESCRIPTION", token, 200, "image").path("photoId").asLong();
    json("POST", drugs(profileId), selection(id, "PRESCRIPTION_PHOTO", code1, code2), token, 400);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM my_drug WHERE profile_id=?", Integer.class, profileId)).isZero();
    json("DELETE", "/api/photos/" + id, null, token, 204);
    vision = prescription("합성약정", "또다른약정");
    id = identify("PRESCRIPTION", token, 200, "image").path("photoId").asLong();
    // 두 카드의 후보 코드가 겹쳐도 카드마다 하나씩 배정할 수 있으면 정상 선택임.
    var result = json("POST", drugs(profileId), selection(id, "PRESCRIPTION_PHOTO", code1, code2), token, 201);
    assertThat(result.path("saved").size()).isEqualTo(2);
  }

  @Test
  void nameSearchCanInspectAndRegisterPermitOnlySyrupWithoutGrainData() throws Exception {
    grainEmpty = true; permitMetadata = true;
    officialRows = List.of(Map.of("ITEM_SEQ", code1, "ITEM_NAME", "합성시럽", "ENTP_NAME", "합성제약",
        "ETC_OTC_NAME", "일반의약품", "MAIN_ITEM_INGR", "[SYN]합성성분"));
    var result = json("GET", "/api/drugs/search?profileId=" + profileId + "&name="
        + URLEncoder.encode("합성시럽", StandardCharsets.UTF_8), null, token, 200);
    assertThat(result.path("content").path(0).path("itemSeq").asString()).isEqualTo(code1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM drug WHERE item_seq=?", Integer.class, code1)).isZero();
    var detail = json("GET", "/api/drugs/" + code1, null, token, 200);
    assertThat(detail.path("name").asString()).isEqualTo("합성시럽");
    assertThat(detail.path("shape").asString()).isEqualTo("미확인");
    assertThat(detail.path("material").asString()).isEqualTo("합성성분");
    var registered = json("POST", drugs(profileId), Map.of("source", "SEARCH", "items", List.of(Map.of("itemSeq", code1))), token, 201);
    assertThat(registered.path("saved").size()).isEqualTo(1);
    assertThat(lookups).anyMatch(s -> s.startsWith(DrugSyncService.PERMIT_INFO_PATH) && s.contains("item_name=합성시럽"));
  }

  @Test
  void invalidCandidatePreservesValidCardWithPartialStatusThroughReloadAndSave() throws Exception {
    var invalid = new LinkedHashMap<String, Object>(officialRows.get(1)); invalid.remove("ETC_OTC_NAME");
    officialRows = List.of(officialRows.getFirst(), invalid);
    var response = identify("PRESCRIPTION", token, 200, "image");
    assertThat(response.path("drugs").path(0).path("lookup").asString()).isEqualTo("PARTIAL");
    assertThat(response.path("drugs").path(0).path("candidates").size()).isEqualTo(1);
    long id = response.path("photoId").asLong();
    var reloaded = json("GET", "/api/photos/" + id + "/candidates", null, token, 200);
    assertThat(reloaded.path("drugs")).isEqualTo(response.path("drugs"));
    json("POST", drugs(profileId), selection(id, "PRESCRIPTION_PHOTO", code1), token, 201);
    assertNoPhotos();
  }

  @Test
  void zeroResultsAreNotFoundAndUnreadableOrInvalidVisionNeverLeavePhotos() throws Exception {
    vision = prescription("없음");
    var notFound = identify("PRESCRIPTION", token, 200, "image");
    assertThat(notFound.path("status").asString()).isEqualTo("NOT_FOUND");
    assertThat(notFound.path("photoId").isNull()).isTrue();
    assertThat(notFound.path("drugs").path(0).path("readName").asString()).isEqualTo("없음");
    assertThat(notFound.path("drugs").path(0).path("lookup").asString()).isEqualTo("NOT_FOUND");
    assertThat(notFound.path("drugs").path(0).path("candidates").isEmpty()).isTrue();
    assertNoPhotos();
    vision = Map.of("readable", false, "medicines", List.of());
    assertThat(identify("MEDICINE_BAG", token, 200, "image").path("status").asString())
        .isEqualTo("UNREADABLE");
    assertNoPhotos();
    vision = Map.of("medicines", List.of());
    identify("PRESCRIPTION", token, 503, "image");
    assertNoPhotos();
  }

  @Test
  void partialPublicFailureStaysPerCardAndAllFailureIs503WithCleanup() throws Exception {
    vision = prescription("합성약정", "실패", "없음");
    var result = identify("PRESCRIPTION", token, 200, "image");
    assertThat(result.path("drugs").path(0).path("lookup").asString()).isEqualTo("OK");
    assertThat(result.path("drugs").path(1).path("lookup").asString()).isEqualTo("FAILED");
    assertThat(result.path("drugs").path(2).path("lookup").asString()).isEqualTo("NOT_FOUND");
    json("DELETE", "/api/photos/" + result.path("photoId").asLong(), null, token, 204);
    vision = prescription("실패");
    identify("PRESCRIPTION", token, 503, "image");
    assertNoPhotos();
  }

  @Test
  void providerFailureDoesNotBecomeSuccessfulEmptyResult() throws Exception {
    visionStatus = 400;
    identify("PRESCRIPTION", token, 503, "image");
    assertNoPhotos();
  }

  @Test
  void concurrentSelectionCreatesOneRowAndReplaySkips() throws Exception {
    long id = identify("PRESCRIPTION", token, 200, "image").path("photoId").asLong();
    var body = selection(id, "PRESCRIPTION_PHOTO", code1);
    var a =
        browser.sendAsync(
            request("POST", drugs(profileId), body, token), HttpResponse.BodyHandlers.ofString());
    var b =
        browser.sendAsync(
            request("POST", drugs(profileId), body, token), HttpResponse.BodyHandlers.ofString());
    assertThat(
            List.of(
                a.get(15, TimeUnit.SECONDS).statusCode(), b.get(15, TimeUnit.SECONDS).statusCode()))
        .containsExactlyInAnyOrder(201, 200);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM my_drug WHERE profile_id=? AND item_seq=?",
                Integer.class,
                profileId,
                code1))
        .isEqualTo(1);
    assertNoPhotos();
  }

  @Test
  void alreadyRegisteredCandidateIsMarkedAndOtherProfileCanStillRegisterSameDrug()
      throws Exception {
    long id = identify("PRESCRIPTION", token, 200, "image").path("photoId").asLong();
    json("POST", drugs(profileId), selection(id, "PRESCRIPTION_PHOTO", code1), token, 201);
    long again = identify("PRESCRIPTION", token, 200, "image").path("photoId").asLong();
    var groups =
        json("GET", "/api/photos/" + again + "/candidates", null, token, 200).path("drugs");
    assertThat(groups.path(0).path("candidates").path(0).path("alreadyRegistered").asBoolean())
        .isTrue();
    json(
        "POST",
        drugs(otherProfileId),
        Map.of("source", "SEARCH", "items", List.of(Map.of("itemSeq", code1))),
        token,
        201);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM my_drug WHERE item_seq=?", Integer.class, code1))
        .isEqualTo(2);
  }

  @Test
  void expiryPreventsLookupAndRegistrationThenCollectorDeletesRowsAndFiles() throws Exception {
    long id = identify("PRESCRIPTION", token, 200, "image").path("photoId").asLong();
    jdbc.update(
        "UPDATE photo SET uploaded_at=? WHERE photo_id=?",
        LocalDateTime.now(TimeZoneConfig.ZONE).minusMinutes(31),
        id);
    json("GET", "/api/photos/" + id + "/candidates", null, token, 400);
    json("POST", drugs(profileId), selection(id, "PRESCRIPTION_PHOTO", code1), token, 400);
    ReflectionTestUtils.setField(lifecycle, "enabled", true);
    try {
      lifecycle.removeExpired();
    } finally {
      ReflectionTestUtils.setField(lifecycle, "enabled", false);
    }
    assertNoPhotos();
  }

  @Test
  void profileDeletionCleansCandidatesLinkedPhotosAndFilesAfterCommit() throws Exception {
    vision = pill("합성약정", "A", "B");
    identify("PILL", token, 200, "front", "back");
    json("DELETE", "/api/profiles/" + profileId, null, token, 204);
    assertNoPhotos();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM profile WHERE profile_id=?", Integer.class, otherProfileId))
        .isEqualTo(1);
  }

  @Test
  void wrongPhotoCountCorruptFileAndUnauthorizedUploadDoNotCallProviders() throws Exception {
    identify("PILL", token, 400, "front");
    identify("PRESCRIPTION", token, 400, "image", "image");
    identify("PRESCRIPTION", null, 401, "image");
    var response = upload("PRESCRIPTION", token, new byte[] {1, 2, 3}, "image");
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(visionRequests).isEmpty();
    assertNoPhotos();
  }

  @Test
  void deletingPhotoWhileVisionIsRunningCannotResurrectCandidateRows() throws Exception {
    entered = new CountDownLatch(1);
    release = new CountDownLatch(1);
    var task =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return upload("PRESCRIPTION", token, png(), "image");
              } catch (Exception e) {
                throw new CompletionException(e);
              }
            });
    try {
      assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
      long id =
          jdbc.queryForObject(
              "SELECT photo_id FROM photo WHERE profile_id=?", Long.class, profileId);
      json("DELETE", "/api/photos/" + id, null, token, 204);
    } finally {
      release.countDown();
    }
    assertThat(task.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(404);
    assertNoPhotos();
  }

  @Test
  void physicalCleanupFailureKeepsCommittedDrugAndCanBeRetried() throws Exception {
    long id = identify("PRESCRIPTION", token, 200, "image").path("photoId").asLong();
    String name =
        jdbc.queryForObject("SELECT temp_path FROM photo WHERE photo_id=?", String.class, id);
    Path path = UPLOAD.resolve(FileStorage.DIR_TEMP).resolve(name);
    Files.delete(path);
    Files.createDirectory(path);
    Files.writeString(path.resolve("blocked"), "fixture");
    try {
      json("POST", drugs(profileId), selection(id, "PRESCRIPTION_PHOTO", code1), token, 201);
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM my_drug WHERE profile_id=?", Integer.class, profileId))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject("SELECT COUNT(*) FROM photo WHERE photo_id=?", Integer.class, id))
          .isEqualTo(1);
    } finally {
      Files.deleteIfExists(path.resolve("blocked"));
      Files.deleteIfExists(path);
    }
    json("DELETE", "/api/photos/" + id, null, token, 204);
    assertNoPhotos();
  }

  @Test
  void photoRemovalRollbackPreservesRowsAndOriginalFile() throws Exception {
    long id = identify("PRESCRIPTION", token, 200, "image").path("photoId").asLong();
    String name =
        jdbc.queryForObject("SELECT temp_path FROM photo WHERE photo_id=?", String.class, id);
    var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      lifecycle.removeForProfile(profileId);
                      throw new IllegalStateException("synthetic rollback");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(Files.exists(UPLOAD.resolve(FileStorage.DIR_TEMP).resolve(name))).isTrue();
    json("GET", "/api/photos/" + id + "/candidates", null, token, 200);
  }

  @Test
  void loginPhotoDrugScheduleCalendarAndChatWorkAsOneOwnedBackendFlow() throws Exception {
    long photo = identify("PRESCRIPTION", token, 200, "image").path("photoId").asLong();
    long drug =
        json("POST", drugs(profileId), selection(photo, "PRESCRIPTION_PHOTO", code1), token, 201)
            .path("saved")
            .path(0)
            .path("myDrugId")
            .asLong();
    String today = LocalDate.now(TimeZoneConfig.ZONE).toString();
    json(
        "PUT",
        drugs(profileId) + "/" + drug + "/schedule",
        Map.of(
            "startDate",
            today,
            "endDate",
            today,
            "doseTimes",
            List.of("08:00", "18:00"),
            "dosePerTime",
            2,
            "timing",
            "식후"),
        token,
        200);
    var calendar = json("GET", "/api/calendar?from=" + today + "&to=" + today, null, token, 200);
    assertThat(calendar.path("schedules").size()).isEqualTo(1);
    assertThat(calendar.path("schedules").path(0).path("dosePerTime").asInt()).isEqualTo(2);
    long session =
        json("POST", "/api/profiles/" + profileId + "/chat-sessions", null, token, 201)
            .path("sessionId")
            .asLong();
    var answer =
        json(
            "POST",
            "/api/chat-sessions/" + session + "/messages",
            Map.of("content", "등록한 합성약의 주의사항을 알려 주세요."),
            token,
            200);
    assertThat(answer.path("result").asString()).isEqualTo("ANSWERED");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_message WHERE session_id=?", Integer.class, session))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT dose_times FROM my_drug WHERE my_drug_id=?", String.class, drug))
        .isEqualTo("08:00,18:00");
    json("DELETE", "/api/profiles/" + profileId, null, token, 204);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_session WHERE session_id=?", Integer.class, session))
        .isZero();
    assertNoPhotos();
  }

  @AfterEach
  void cleanup() throws Exception {
    release.countDown();
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (ACTIVE.get() > 0 && System.nanoTime() < until) Thread.sleep(20);
    @SuppressWarnings("unchecked")
    var inFlight = (Set<String>) ReflectionTestUtils.getField(durPrefetch, "inFlight");
    until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while ((inFlight.contains(code1) || inFlight.contains(code2)) && System.nanoTime() < until)
      Thread.sleep(20);
    assertThat(inFlight).doesNotContain(code1, code2);
    for (long m : List.of(memberId, foreignMember)) {
      jdbc.update(
          "DELETE x FROM chat_summary x JOIN chat_session s ON s.session_id=x.session_id WHERE"
              + " s.member_id=?",
          m);
      jdbc.update(
          "DELETE x FROM chat_message x JOIN chat_session s ON s.session_id=x.session_id WHERE"
              + " s.member_id=?",
          m);
      jdbc.update("DELETE FROM chat_session WHERE member_id=?", m);
      jdbc.update(
          "DELETE c FROM ocr_candidate c JOIN photo p ON p.photo_id=c.photo_id WHERE p.member_id=?",
          m);
      var names =
          jdbc.queryForList("SELECT temp_path FROM photo WHERE member_id=?", String.class, m);
      jdbc.update("DELETE FROM photo WHERE member_id=?", m);
      names.forEach(files::deleteTemporaryImage);
      jdbc.update(
          "DELETE d FROM my_drug d JOIN profile p ON p.profile_id=d.profile_id WHERE p.member_id=?",
          m);
      jdbc.update("DELETE FROM profile WHERE member_id=?", m);
      jdbc.update("DELETE FROM member WHERE member_id=?", m);
    }
    jdbc.update("DELETE FROM dur_warning WHERE item_seq IN (?,?)", code1, code2);
    jdbc.update(
        "DELETE FROM dur_interaction WHERE item_seq2 IN (?,?) OR mixture_item_seq IN (?,?)",
        code1,
        code2,
        code1,
        code2);
    jdbc.update("DELETE FROM drug WHERE item_seq IN (?,?)", code1, code2);
    browser.close();
  }

  @AfterAll
  static void close() throws IOException {
    if (provider != null) provider.stop(0);
    if (workers != null) workers.close();
    try (var paths = Files.walk(UPLOAD)) {
      for (var p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
    }
  }

  void assertNoPhotos() throws IOException {
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM photo WHERE member_id=?", Integer.class, memberId))
        .isZero();
    Path folder = UPLOAD.resolve(FileStorage.DIR_TEMP);
    if (Files.exists(folder))
      try (var paths = Files.list(folder)) {
        assertThat(paths.toList()).isEmpty();
      }
  }

  JsonNode identify(String kind, String auth, int expected, String... parts) throws Exception {
    var r = upload(kind, auth, png(), parts);
    assertThat(r.statusCode()).as(r.body()).isEqualTo(expected);
    return JSON.readTree(r.body());
  }

  HttpResponse<String> upload(String kind, String auth, byte[] image, String... parts)
      throws Exception {
    String boundary = "photo-test-boundary";
    var out = new ByteArrayOutputStream();
    for (String name : parts) {
      out.write(
          ("--"
                  + boundary
                  + "\r\nContent-Disposition: form-data; name=\""
                  + name
                  + "\"; filename=\"patient-original.png\"\r\nContent-Type: image/png\r\n\r\n")
              .getBytes(StandardCharsets.UTF_8));
      out.write(image);
      out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }
    out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    var builder =
        HttpRequest.newBuilder(
                URI.create(
                    url("/api/profiles/" + profileId + "/photo-identifications?kind=" + kind)))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()));
    if (auth != null) builder.header("Authorization", "Bearer " + auth);
    return browser.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  JsonNode json(String method, String path, Object body, String auth, int expected)
      throws Exception {
    var response = raw(method, path, body, auth);
    assertThat(response.statusCode())
        .as("%s %s %s", method, path, response.body())
        .isEqualTo(expected);
    return response.body().isBlank() ? JSON.nullNode() : JSON.readTree(response.body());
  }

  HttpResponse<String> raw(String method, String path, Object body, String auth) throws Exception {
    return browser.send(request(method, path, body, auth), HttpResponse.BodyHandlers.ofString());
  }

  HttpRequest request(String method, String path, Object body, String auth) {
    var b = HttpRequest.newBuilder(URI.create(url(path))).timeout(Duration.ofSeconds(20));
    if (auth != null) b.header("Authorization", "Bearer " + auth);
    if (body != null) b.header("Content-Type", "application/json");
    return b.method(
            method,
            body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
        .build();
  }

  String url(String path) {
    return "http://127.0.0.1:" + port + path;
  }

  static String drugs(long id) {
    return "/api/profiles/" + id + "/drugs";
  }

  static Map<String, Object> selection(long photo, String source, String... codes) {
    return Map.of(
        "photoId",
        photo,
        "source",
        source,
        "items",
        Arrays.stream(codes).map(c -> Map.of("itemSeq", c)).toList());
  }

  long member(String email) {
    return insert(
        "INSERT INTO member(email,password_hash,nickname,user_role,created_at,updated_at)"
            + " VALUES(?,?,'합성회원','USER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
        email,
        passwords.encode("fixture-pass-123"));
  }

  long profile(long member) {
    return insert(
        "INSERT INTO"
            + " profile(member_id,profile_name,relationship_type,profile_birth_year,profile_gender,profile_pregnant,created_at,updated_at)"
            + " VALUES(?,'합성프로필','SELF',1990,'FEMALE',FALSE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
        member);
  }

  long insert(String sql, Object... args) {
    var key = new GeneratedKeyHolder();
    jdbc.update(
        c -> {
          var s = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
          for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
          return s;
        },
        key);
    return key.getKey().longValue();
  }

  static byte[] png() throws IOException {
    var out = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(20, 30, BufferedImage.TYPE_INT_RGB), "png", out);
    return out.toByteArray();
  }

  static Path tempDirectory() {
    try {
      return Files.createTempDirectory("pill-photo-http-");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static Object prescription(String... names) {
    return Map.of(
        "readable",
        true,
        "medicines",
        Arrays.stream(names).map(n -> Map.of("readName", n, "readMaker", "")).toList());
  }

  static Object pill(String name, String front, String back) {
    var m = new LinkedHashMap<String, Object>();
    m.put("singlePill", true);
    for (String k :
        List.of(
            "itemName",
            "entpName",
            "printFront",
            "printBack",
            "shape",
            "colorClass1",
            "colorClass2",
            "lineFront",
            "lineBack")) m.put(k, "");
    m.put("itemName", name);
    m.put("printFront", front);
    m.put("printBack", back);
    return m;
  }

  static Map<String, Object> row(String code, String name, String front, String back) {
    return Map.of(
        "ITEM_SEQ",
        code,
        "ITEM_NAME",
        name,
        "ENTP_NAME",
        "합성제약",
        "ETC_OTC_NAME",
        "일반의약품",
        "PRINT_FRONT",
        front,
        "PRINT_BACK",
        back,
        "DRUG_SHAPE",
        "원형",
        "COLOR_CLASS1",
        "흰색",
        "FORM_CODE_NAME",
        "정제",
        "CLASS_NAME",
        "해열진통제");
  }

  static void handle(HttpExchange exchange) throws IOException {
    ACTIVE.incrementAndGet();
    try {
      var t = current;
      String path = exchange.getRequestURI().getPath();
      if (path.equals("/responses")) {
        var request = JSON.readTree(exchange.getRequestBody().readAllBytes());
        boolean isVision = "synthetic-photo-vision".equals(request.path("model").asString());
        if (isVision) {
          t.visionRequests.add(request);
          t.sawUploaded =
              t.jdbc.queryForObject(
                      "SELECT COUNT(*) FROM photo WHERE profile_id=? AND status='UPLOADED'",
                      Integer.class,
                      t.profileId)
                  > 0;
          t.entered.countDown();
          if (!t.release.await(10, TimeUnit.SECONDS))
            throw new IllegalStateException("synthetic gate timeout");
        }
        respond(
            exchange,
            isVision ? t.visionStatus : 200,
            Map.of(
                "status",
                "completed",
                "output",
                List.of(
                    Map.of(
                        "type",
                        "message",
                        "content",
                        List.of(
                            Map.of(
                                "type",
                                "output_text",
                                "text",
                                isVision
                                    ? JSON.writeValueAsString(t.vision)
                                    : "합성 복약 안내입니다. 공식 주의사항을 확인하고 궁금한 점은 약사에게 문의하세요."))))));
      } else if (path.equals("/decisions")) {
        respond(
            exchange,
            200,
            Map.of(
                "answers",
                List.of(
                    Map.of("type", "choice", "name", "medication_category", "choice", "PASS"))));
      } else if (path.startsWith("/1471000/")) {
        String query =
            URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
        t.lookups.add(path + "?" + query.replaceAll("serviceKey=[^&]*", "serviceKey=REDACTED"));
        if (path.contains("DrbEasyDrugInfoService")) {
          respond(
              exchange,
              200,
              Map.of(
                  "header",
                  Map.of("resultCode", "00"),
                  "body",
                  Map.of(
                      "totalCount",
                      1,
                      "items",
                      List.of(
                          Map.of(
                              "itemSeq",
                              t.code1,
                              "efcyQesitm",
                              "합성 공식 효능",
                              "updateDe",
                              "2026-10-20")))));
        } else if (path.contains("getDrugPrdtPrmsnDtlInq08")) {
          var detail = new LinkedHashMap<String, Object>();
          detail.put("ITEM_SEQ", t.code1); detail.put("EE_DOC_DATA", "<DOC>합성 허가 근거</DOC>"); detail.put("CHANGE_DATE", "20261020");
          if (t.permitMetadata && !t.officialRows.isEmpty()) detail.putAll(t.officialRows.getFirst());
          respond(
              exchange,
              200,
              Map.of(
                  "header",
                  Map.of("resultCode", "00"),
                  "body",
                  Map.of(
                      "totalCount",
                      1,
                      "items",
                      List.of(detail))));
        } else if (path.contains("DURPrdlst"))
          respond(
              exchange,
              200,
              Map.of(
                  "header",
                  Map.of("resultCode", "00"),
                  "body",
                  Map.of("totalCount", 0, "items", List.of())));
        else if (query.contains("item_name=실패"))
          respond(exchange, 400, Map.of("error", "synthetic_public_failure"));
        else {
          var rows = query.contains("item_name=없음") || (t.grainEmpty && path.contains("MdcinGrn")) ? List.of() : t.officialRows;
          respond(
              exchange,
              200,
              Map.of(
                  "header",
                  Map.of("resultCode", "00"),
                  "body",
                  Map.of("totalCount", rows.size(), "items", rows)));
        }
      } else respond(exchange, 404, Map.of());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      exchange.close();
      ACTIVE.decrementAndGet();
    }
  }

  static void respond(HttpExchange exchange, int status, Object data) throws IOException {
    byte[] bytes = JSON.writeValueAsString(data).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
  }
}
