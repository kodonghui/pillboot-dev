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
  @Autowired org.semiproject.pillbadeumserver.image.service.PhotoIdentificationWorkflow workflow;
  @Autowired org.semiproject.pillbadeumserver.image.service.PillCatalog catalog;
  @Autowired org.semiproject.pillbadeumserver.image.service.PillIdentificationSessions sessions;
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
  volatile long visionDelayMs;
  volatile List<Map<String, Object>> officialRows;
  volatile boolean sawUploaded;
  volatile boolean grainEmpty, permitMetadata, durWarning;
  volatile CountDownLatch entered = new CountDownLatch(0), release = new CountDownLatch(0);

  @DynamicPropertySource
  static void providers(DynamicPropertyRegistry r) {
    r.add("openai.base-url", PhotoFlowHttpMySqlTests::providerUrl);
    r.add("openai.api-key", () -> "synthetic-photo-ai-key");
    r.add("openai.model.vision", () -> "synthetic-photo-vision");
    r.add("openai.model.prescription", () -> "synthetic-photo-vision");
    r.add("photo.prescription-ocr-timeout-ms", () -> 60000);
    r.add("openai.model.chat", () -> "synthetic-photo-chat");
    r.add("openai.model.decision", () -> "synthetic-photo-decision");
    r.add("openai.reasoning-effort", () -> "");
    r.add("public-data.base-url", PhotoFlowHttpMySqlTests::providerUrl);
    r.add("public-data.service-key", () -> "synthetic-photo-public-key");
    r.add("file.upload.mac.path", () -> UPLOAD.toString());
    r.add("file.upload.windows.path", () -> UPLOAD.toString());
    r.add("qdrant.enabled", () -> false);
    r.add("pill-identification.catalog-file", () -> UPLOAD.resolve("catalog.json").toString());
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

  @Test
  void mixedPrescriptionSelectionCommitsEachOriginAndCleansPhotoAfterCommit() throws Exception {
    var photo = mixedPhoto();
    long id = photo.path("photoId").asLong();
    var result = json("POST", "/api/profiles/" + profileId + "/drugs", mixedBody(id, code2), token, 201);
    assertThat(result.path("saved")).hasSize(2);
    assertThat(jdbc.queryForObject("SELECT source FROM my_drug WHERE profile_id=? AND item_seq=?", String.class, profileId, code1)).isEqualTo("PRESCRIPTION_PHOTO");
    assertThat(jdbc.queryForObject("SELECT source FROM my_drug WHERE profile_id=? AND item_seq=?", String.class, profileId, code2)).isEqualTo("SEARCH");
    assertNoPhotos();
    var repeated = json("POST", "/api/profiles/" + profileId + "/drugs", mixedBody(id, code2), token, 200);
    assertThat(repeated.path("skipped")).hasSize(2);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM my_drug WHERE profile_id=?", Integer.class, profileId)).isEqualTo(2);
  }

  @Test
  void failedSupplementalSearchLeavesNoMedicationAndPreservesPhoto() throws Exception {
    var photo = mixedPhoto(); long id = photo.path("photoId").asLong();
    json("POST", "/api/profiles/" + profileId + "/drugs", mixedBody(id, code2 + "99"), token, 404);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM my_drug WHERE profile_id=?", Integer.class, profileId)).isZero();
    json("GET", "/api/photos/" + id + "/candidates", null, token, 200);
    json("DELETE", "/api/photos/" + id, null, token, 204);
  }

  @Test
  void correctedSearchCannotPretendToBeAPhotoCandidateOrReplaceTheSameSlot() throws Exception {
    var photo = mixedPhoto(); long id = photo.path("photoId").asLong();
    var forged = Map.of("source", "PRESCRIPTION_PHOTO", "photoId", id, "items", List.of(
        Map.of("itemSeq", code1, "readNo", 1), Map.of("itemSeq", code2, "source", "PRESCRIPTION_PHOTO", "readNo", 2)));
    json("POST", "/api/profiles/" + profileId + "/drugs", forged, token, 400);
    var sameSlot = Map.of("source", "PRESCRIPTION_PHOTO", "photoId", id, "items", List.of(
        Map.of("itemSeq", code1, "readNo", 1), Map.of("itemSeq", code2, "source", "SEARCH", "readNo", 1)));
    json("POST", "/api/profiles/" + profileId + "/drugs", sameSlot, token, 400);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM my_drug WHERE profile_id=?", Integer.class, profileId)).isZero();
    json("DELETE", "/api/photos/" + id, null, token, 204);
  }

  @Test
  void allNotFoundPrescriptionCanSaveCorrectedSearchWithoutAPhoto() throws Exception {
    vision = prescription("없음");
    var photo = identify("PRESCRIPTION", token, 200, "image");
    assertThat(photo.path("photoId").isNull()).isTrue();
    var row = new LinkedHashMap<String, Object>(officialRows.getFirst()); row.put("MAIN_ITEM_INGR", "한글성분");
    officialRows = List.of(row);
    var body = Map.of("source", "PRESCRIPTION_PHOTO", "items", List.of(Map.of("itemSeq", code1, "source", "SEARCH", "readNo", 1)));
    json("POST", "/api/profiles/" + profileId + "/drugs", body, token, 201);
    assertThat(jdbc.queryForObject("SELECT source FROM my_drug WHERE profile_id=?", String.class, profileId)).isEqualTo("SEARCH");
    assertNoPhotos();
  }

  @Test
  void koreanIngredientLongerThanOneHundredCharactersSurvivesCandidateReloadAndRegistration() throws Exception {
    String ingredient = "아세트아미노펜, 카페인무수물, 복합한글성분, ".repeat(16).strip();
    assertThat(ingredient.length()).isGreaterThan(100);
    var row = new LinkedHashMap<String, Object>(officialRows.getFirst());
    row.put("MAIN_ITEM_INGR", ingredient); row.put("ITEM_INGR_NAME", "English alternative");
    officialRows = List.of(row);
    var photo = identify("PRESCRIPTION", token, 200, "image"); long id = photo.path("photoId").asLong();
    assertThat(photo.path("drugs").get(0).path("candidates").get(0).path("ingredientName").asString()).isEqualTo(ingredient);
    assertThat(json("GET", "/api/photos/" + id + "/candidates", null, token, 200)
        .path("drugs").get(0).path("candidates").get(0).path("ingredientName").asString()).isEqualTo(ingredient);
    json("POST", "/api/profiles/" + profileId + "/drugs", Map.of("source", "PRESCRIPTION_PHOTO", "photoId", id,
        "items", List.of(Map.of("itemSeq", code1))), token, 201);
    assertThat(jdbc.queryForObject("SELECT material FROM drug WHERE item_seq=?", String.class, code1)).isEqualTo(ingredient);
  }

  @Test
  void futureBirthYearIsRejectedForBothProfileCreationAndUpdate() throws Exception {
    var body = Map.of("profileName", "미래프로필", "relationshipType", "SELF", "profileBirthYear", LocalDate.now(TimeZoneConfig.ZONE).getYear() + 1,
        "profileGender", "FEMALE", "profilePregnant", false);
    int before = jdbc.queryForObject("SELECT COUNT(*) FROM profile WHERE member_id=?", Integer.class, memberId);
    int originalYear = jdbc.queryForObject("SELECT profile_birth_year FROM profile WHERE profile_id=?", Integer.class, profileId);
    json("POST", "/api/profiles", body, token, 400);
    json("PUT", "/api/profiles/" + profileId, body, token, 400);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM profile WHERE member_id=?", Integer.class, memberId)).isEqualTo(before);
    assertThat(jdbc.queryForObject("SELECT profile_birth_year FROM profile WHERE profile_id=?", Integer.class, profileId)).isEqualTo(originalYear);
    var currentYear = new LinkedHashMap<String, Object>(body);
    currentYear.put("profileBirthYear", LocalDate.now(TimeZoneConfig.ZONE).getYear());
    assertThat(json("POST", "/api/profiles", currentYear, token, 201).path("profileBirthYear").asInt())
        .isEqualTo(currentYear.get("profileBirthYear"));
  }

  @Test
  void pillOcrCanFinishAfterThirtySecondsAndExpiredBudgetDoesNotRetry() throws Exception {
    vision = pill(null, "A", "B"); visionDelayMs = 31_000;
    var response = upload("PILL", token, png(), Duration.ofSeconds(90), "front", "back");
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    assertThat(visionRequests).hasSize(1);
    long id = JSON.readTree(response.body()).path("photoId").asLong();
    json("DELETE", "/api/photos/" + id, null, token, 204);
    long original = (long)ReflectionTestUtils.getField(workflow, "pillTimeoutMs");
    try {
      ReflectionTestUtils.setField(workflow, "pillTimeoutMs", 300L); visionDelayMs = 2000; visionRequests.clear();
      identify("PILL", token, 503, "front", "back");
      assertThat(visionRequests).hasSize(1); assertNoPhotos();
    } finally { ReflectionTestUtils.setField(workflow, "pillTimeoutMs", original); }
  }

  private JsonNode mixedPhoto() throws Exception {
    var first = officialRows.getFirst(); var second = new LinkedHashMap<String, Object>(officialRows.get(1));
    second.put("MAIN_ITEM_INGR", "검색성분");
    officialRows = List.of(first); vision = prescription("합성약정", "없음");
    var photo = identify("PRESCRIPTION", token, 200, "image");
    officialRows = List.of(first, second); return photo;
  }

  private Map<String, Object> mixedBody(long photoId, String searchedCode) {
    return Map.of("source", "PRESCRIPTION_PHOTO", "photoId", photoId, "items", List.of(
        Map.of("itemSeq", code1, "source", "PRESCRIPTION_PHOTO", "readNo", 1),
        Map.of("itemSeq", searchedCode, "source", "SEARCH", "readNo", 2)));
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
    catalog.refresh();
    lookups.clear();
  }

  @Test
  void prescriptionOcrBeyondThirtySecondsReturnsSavedCandidatesAndReloads() throws Exception {
    visionDelayMs = 31_000;
    long started = System.nanoTime();
    var response = upload("PRESCRIPTION", token, png(), Duration.ofSeconds(70), "image");
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThan(Duration.ofSeconds(30));
    var result = JSON.readTree(response.body());
    long id = result.path("photoId").asLong();
    assertThat(visionRequests).hasSize(1);
    assertThat(json("GET", "/api/photos/" + id + "/candidates", null, token, 200).path("drugs"))
        .isEqualTo(result.path("drugs"));
    json("DELETE", "/api/photos/" + id, null, token, 204);
    assertNoPhotos();
  }

  @Test
  void expiredPrescriptionOcrCancelsAndRemovesUploadedPhoto() throws Exception {
    long original = (long) ReflectionTestUtils.getField(workflow, "prescriptionOcrTimeoutMs");
    try {
      ReflectionTestUtils.setField(workflow, "prescriptionOcrTimeoutMs", 500L);
      visionDelayMs = 2000;
      identify("PRESCRIPTION", token, 503, "image");
      assertNoPhotos();
      assertThat(visionRequests).hasSize(1);
    } finally {
      ReflectionTestUtils.setField(workflow, "prescriptionOcrTimeoutMs", original);
    }
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
    var registered = json("POST", drugs(profileId), selection(id, "PILL_PHOTO", code1), token, 201);
    long drug = registered.path("saved").path(0).path("myDrugId").asLong();
    var detail = json("GET", drugs(profileId) + "/" + drug, null, token, 200);
    assertThat(detail.path("dosePerTime").isNull()).isTrue();
    var list = json("GET", drugs(profileId), null, token, 200);
    assertThat(list.path(0).path("dosePerTime").isNull()).isTrue();
    var choices = json("GET", "/api/profiles/" + profileId + "/drugs/schedule-choices", null, token, 200);
    assertThat(choices.path("drugs").path(0).path("dosePerTime").isNull()).isTrue();
    var schedule = Map.of("startDate", "2026-10-21", "endDate", "2026-10-22", "doseTimes", List.of("08:00"), "dosePerTime", 2);
    json("PUT", drugs(profileId) + "/" + drug + "/schedule", schedule, token, 200);
    assertThat(json("GET", drugs(profileId) + "/" + drug, null, token, 200).path("dosePerTime").asInt()).isEqualTo(2);
    json("DELETE", drugs(profileId) + "/" + drug + "/schedule", null, token, 204);
    assertThat(json("GET", drugs(profileId) + "/" + drug, null, token, 200).path("dosePerTime").asInt()).isEqualTo(2);
    assertNoPhotos();
  }

  @Test
  void sparsePermitCacheIsEnrichedByLaterPillMetadataAndKeepsRicherValues() throws Exception {
    long first = identify("PRESCRIPTION", token, 200, "image").path("photoId").asLong();
    jdbc.update("UPDATE drug SET shape='미확인',image_url=NULL,material='기존상세성분' WHERE item_seq=?", code1);
    json("DELETE", "/api/photos/" + first, null, token, 204);
    var richer = new LinkedHashMap<String, Object>(officialRows.getFirst()); richer.put("ITEM_IMAGE", "https://example.test/synthetic.png");
    officialRows = List.of(richer); vision = pill("합성약정", "A", "B");
    catalog.refresh();
    identify("PILL", token, 200, "front", "back");
    assertThat(jdbc.queryForObject("SELECT shape FROM drug WHERE item_seq=?", String.class, code1)).isEqualTo("원형");
    assertThat(jdbc.queryForObject("SELECT material FROM drug WHERE item_seq=?", String.class, code1)).isEqualTo("기존상세성분");
    assertThat(jdbc.queryForObject("SELECT image_url FROM drug WHERE item_seq=?", String.class, code1)).endsWith("synthetic.png");
    var detail = json("GET", "/api/drugs/" + code1, null, token, 200);
    assertThat(detail.path("sourceName").asString()).contains("낱알식별·제품 허가정보");
  }

  @Test
  void imprintOnlyCanIdentifyFromPreparedCatalogWithoutExternalLookup() throws Exception {
    vision = pill("", "A", "B");
    var result = identify("PILL", token, 200, "front", "back");
    assertThat(result.path("status").asString()).isEqualTo("OK");
    assertThat(result.path("candidates").path(0).path("itemSeq").asString()).isEqualTo(code1);
    assertThat(result.path("candidates").path(0).path("printMatched").asBoolean()).isTrue();
    assertThat(lookups).isEmpty();
    json("DELETE", "/api/photos/" + result.path("photoId").asLong(), null, token, 204);
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
        "SPCLTY_PBLC", "일반의약품", "MAIN_ITEM_INGR", "[SYN]합성성분"));
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
  void concurrentDifferentProfileDeletesPreserveLastProfileInMysql() throws Exception {
    var start = new CountDownLatch(1);
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var left = pool.submit(() -> { start.await(); return raw("DELETE", "/api/profiles/" + profileId, null, token); });
      var right = pool.submit(() -> { start.await(); return raw("DELETE", "/api/profiles/" + otherProfileId, null, token); });
      start.countDown();
      var responses = List.of(left.get(10, TimeUnit.SECONDS), right.get(10, TimeUnit.SECONDS));
      assertThat(responses.stream().map(HttpResponse::statusCode)).containsExactlyInAnyOrder(204, 409);
      assertThat(responses.stream().filter(r -> r.statusCode() == 409).findFirst().orElseThrow().body()).contains("LAST_PROFILE");
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM profile WHERE member_id=?", Integer.class, memberId)).isEqualTo(1);
    }
  }

  @Test
  void answeredDurSnapshotReloadsFromMysqlWithoutProviderRequery() throws Exception {
    durWarning = true;
    long photo = identify("PRESCRIPTION", token, 200, "image").path("photoId").asLong();
    json("POST", drugs(profileId), selection(photo, "PRESCRIPTION_PHOTO", code1), token, 201);
    long session = json("POST", "/api/profiles/" + profileId + "/chat-sessions", null, token, 201).path("sessionId").asLong();
    var answered = json("POST", "/api/chat-sessions/" + session + "/messages", Map.of("content", "등록 약 주의사항"), token, 200);
    var notice = answered.path("answer").path("durNotice");
    assertThat(notice.path("result").asString()).isEqualTo("FOUND");
    assertThat(notice.path("items").path(0).path("updatedAt").asString()).isEqualTo("2026-10-20");
    assertThat(jdbc.queryForObject("SELECT JSON_EXTRACT(dur_notice,'$.result') FROM chat_message WHERE session_id=? AND role='assistant'", String.class, session)).contains("FOUND");
    int calls = lookups.size();
    var history = json("GET", "/api/chat-sessions/" + session + "/messages", null, token, 200);
    assertThat(history.path("messages").path(1).path("durNotice")).isEqualTo(notice);
    assertThat(lookups).hasSize(calls);
  }

  @Test
  void cancellationIsRankedLastAndPreservedAcrossPhotoReload() throws Exception {
    var cancelled = new java.util.LinkedHashMap<>(officialRows.getFirst()); cancelled.put("CANCEL_NAME", "취하");
    var normal = new java.util.LinkedHashMap<>(officialRows.get(1)); normal.put("CANCEL_NAME", "정상");
    officialRows = List.of(cancelled, normal);
    var identified = identify("PRESCRIPTION", token, 200, "image");
    var candidates = identified.path("drugs").path(0).path("candidates");
    assertThat(candidates.path(0).path("itemSeq").asString()).isEqualTo(code2);
    assertThat(candidates.path(0).path("cancelled").asBoolean()).isFalse();
    assertThat(candidates.path(1).path("cancelled").asBoolean()).isTrue();
    var reloaded = json("GET", "/api/photos/" + identified.path("photoId").asLong() + "/candidates", null, token, 200);
    assertThat(reloaded.path("drugs").path(0).path("candidates")).isEqualTo(candidates);
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


  @Test
  void detailedCluesRequireImprintShapeColorAndNeverCallVision() throws Exception {
    for (String field : List.of("printFront", "shape", "colorClass1")) {
      var input = clueInput("A", null);
      input.remove(field);
      var result = clueSearch(input);
      assertThat(result.path("status").asString()).isEqualTo("UNREADABLE");
      assertThat(result.path("missingFields").size()).isEqualTo(1);
      assertThat(result.path("selectionAllowed").asBoolean()).isFalse();
      assertThat(result.path("candidates")).isEmpty();
      assertThat(result.path("nextActions").toString()).contains("EDIT_CLUES", "RETAKE", "SEARCH_BY_NAME");
      assertThat(result.path("identificationId").asString()).isNotBlank();
    }
    assertThat(visionRequests).isEmpty();
    assertThat(lookups).isEmpty();
    assertNoPhotos();
  }

  @Test
  void malformedAndLegacyRequestsHaveDifferentContractsFromBusinessFailure() throws Exception {
    json("POST", "/api/images/identify", Map.of("shape", "원형", "colorClass1", "흰색"), token, 400);
    var valid = clueInput("A", "B");
    var legacy = json("POST", "/api/images/identify?view=legacy", valid, token, 200);
    assertThat(legacy.isArray()).isTrue();
    assertThat(legacy.size()).isEqualTo(1);
    assertThat(legacy.path(0).path("similarityScore").isNull()).isTrue();
    assertThat(legacy.path(0).path("matchDetails")).isNotEmpty();
    json("POST", "/api/images/identify?view=nope", valid, token, 400);
    json("POST", "/api/images/identify?view=details", valid, token, 400);
    valid.put("printFrontState", "NONE");
    json("POST", cluePath(), valid, token, 400);
    valid.put("printFrontState", "not-a-state");
    json("POST", cluePath(), valid, token, 400);
    valid.remove("printFrontState"); valid.put("printFront", 123);
    json("POST", cluePath(), valid, token, 400);
    valid.put("printFront", "A".repeat(51));
    json("POST", cluePath(), valid, token, 400);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pill_identification_session WHERE profile_id=?", Integer.class, profileId)).isZero();
    assertThat(visionRequests).isEmpty();
  }

  @Test
  void validZeroIsNotFoundButNoneAndUnreadableRemainDistinct() throws Exception {
    var unknown = clueSearch(clueInput("ZZ", null));
    assertThat(unknown.path("status").asString()).isEqualTo("NOT_FOUND");
    assertThat(unknown.path("reason").asString()).isEqualTo("NO_MATCH");
    assertThat(unknown.path("missingFields")).isEmpty();
    var input = clueInput(null, null);
    input.put("printFrontState", "NONE"); input.put("printBackState", "UNREADABLE");
    var missing = clueSearch(input);
    assertThat(missing.path("clues").path("printFrontState").asString()).isEqualTo("NONE");
    assertThat(missing.path("clues").path("printBackState").asString()).isEqualTo("UNREADABLE");
    assertThat(missing.path("missingFields").toString()).contains("IMPRINT");
    input.put("printFrontState", "READABLE"); input.put("printFront", "A?");
    assertThat(clueSearch(input).path("status").asString()).isEqualTo("UNREADABLE");
  }

  @Test
  void fullCountIsDeduplicatedBeforeFivePreviewLimitAndCannotBeSaved() throws Exception {
    var rows = new ArrayList<Map<String, Object>>();
    rows.add(row(code1, "합성약정", "A", "B"));
    rows.add(row(code1, "중복행", "A", "B"));
    rows.add(row(code2, "대안약정", "A", "B"));
    for (int i = 1; i <= 5; i++) rows.add(row(code1 + i + "99", "대안" + i, "A", "B"));
    officialRows = rows; catalog.refresh();
    var result = clueSearch(clueInput("A", null));
    assertThat(result.path("totalCandidateCount").asInt()).isEqualTo(7);
    assertThat(result.path("returnedCandidateCount").asInt()).isEqualTo(5);
    assertThat(result.path("candidateLimit").asInt()).isEqualTo(5);
    assertThat(result.path("hasMoreCandidates").asBoolean()).isTrue();
    assertThat(result.path("hasTiedCandidates").asBoolean()).isTrue();
    assertThat(result.path("requiresRefinement").asBoolean()).isTrue();
    assertThat(result.path("selectionAllowed").asBoolean()).isFalse();
    assertThat(result.path("reason").asString()).isEqualTo("TOO_MANY_CANDIDATES");
    assertThat(result.path("candidates").findValuesAsString("itemSeq")).doesNotHaveDuplicates();
    String returned = result.path("candidates").path(0).path("itemSeq").asString();
    json("POST", drugs(profileId), resultSelection(result, returned), token, 409);
  }

  @Test
  void cluesRestoreFrozenEvidenceAndRefreshOnlyCurrentRegistrationFlags() throws Exception {
    var original = clueSearch(clueInput("A", "B"));
    assertThat(original.path("registrationSource").asString()).isEqualTo("PILL_CLUES");
    assertThat(original.path("origin").asString()).isEqualTo("CLUES");
    assertThat(original.path("photoId").isNull()).isTrue();
    assertThat(original.path("candidates").path(0).has("similarityScore")).isFalse();
    var http = raw("GET", resultPath(original), null, token);
    assertThat(http.headers().firstValue("X-Pill-Identification-Version")).contains("1");
    var restored = JSON.readTree(http.body());
    assertThat(restored).isEqualTo(original);
    var changed = new LinkedHashMap<String, Object>(row(code1, "갱신된이름", "X", "Y"));
    changed.put("MAIN_ITEM_INGR", "합성성분");
    officialRows = List.of(changed); catalog.refresh();
    assertThat(json("GET", resultPath(original), null, token, 200)).isEqualTo(original);
    // 다른 정상 경로에서 등록한 뒤에도 저장 당시 후보·근거는 바뀌지 않음.
    json("POST", drugs(profileId), Map.of("source", "SEARCH", "items", List.of(Map.of("itemSeq", code1))), token, 201);
    var refreshed = json("GET", resultPath(original), null, token, 200);
    assertThat(refreshed.path("candidates").path(0).path("name").asString()).isEqualTo("합성약정");
    assertThat(refreshed.path("candidates").path(0).path("matchDetails")).isEqualTo(original.path("candidates").path(0).path("matchDetails"));
    assertThat(refreshed.path("candidates").path(0).path("alreadyRegistered").asBoolean()).isTrue();
    assertThat(refreshed.path("selectionAllowed").asBoolean()).isFalse();
    assertThat(refreshed.path("reason").asString()).isEqualTo("ALL_ALREADY_REGISTERED");
    assertThat(refreshed.path("expiresAt")).isEqualTo(original.path("expiresAt"));
    assertThat(visionRequests).isEmpty();
  }

  @Test
  void clueRegistrationValidatesSourceMembershipAndOneSelectionBeforeCommittingReceipt() throws Exception {
    officialRows = List.of(row(code1, "후보1", "A", "B"), row(code2, "후보2", "A", "B")); catalog.refresh();
    var result = clueSearch(clueInput("A", "B"));
    var forged = resultSelection(result, code1); forged.put("source", "PILL_PHOTO"); forged.put("photoId", 99999);
    json("POST", drugs(profileId), forged, token, 400);
    forged = resultSelection(result, code1); forged.put("source", "SEARCH");
    json("POST", drugs(profileId), forged, token, 400);
    json("POST", drugs(profileId), resultSelection(result, code1 + "999999"), token, 400);
    forged = resultSelection(result, code1);
    forged.put("items", List.of(Map.of("itemSeq", code1), Map.of("itemSeq", code2)));
    json("POST", drugs(profileId), forged, token, 400);
    forged.put("items", List.of(Map.of("itemSeq", code1), Map.of("itemSeq", code1)));
    json("POST", drugs(profileId), forged, token, 400);
    var body = resultSelection(result, code1);
    var receipt = json("POST", drugs(profileId), body, token, 201);
    long myDrug = receipt.path("saved").path(0).path("myDrugId").asLong();
    assertThat(json("GET", drugs(profileId) + "/" + myDrug, null, token, 200).path("source").asString()).isEqualTo("PILL_CLUES");
    assertThat(jdbc.queryForObject("SELECT snapshot IS NULL FROM pill_identification_session WHERE identification_id=?", Boolean.class,
        result.path("identificationId").asString())).isTrue();
    assertThat(json("POST", drugs(profileId), body, token, 201)).isEqualTo(receipt);
    json("POST", drugs(profileId), resultSelection(result, code2), token, 409);
    json("DELETE", drugs(profileId) + "/" + myDrug, null, token, 204);
    assertThat(json("POST", drugs(profileId), body, token, 201)).isEqualTo(receipt);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM my_drug WHERE profile_id=?", Integer.class, profileId)).isZero();
    var consumed = json("GET", resultPath(result), null, token, 200);
    assertThat(consumed.path("lifecycle").asString()).isEqualTo("CONSUMED");
    assertThat(consumed.path("registrationReceipt").path("httpStatus").asInt()).isEqualTo(201);
    assertThat(consumed.has("clues")).isFalse();
  }

  @Test
  void concurrentSameResultRegistrationReturnsTheFirstReceiptOnce() throws Exception {
    var result = clueSearch(clueInput("A", "B"));
    var body = resultSelection(result, code1);
    try (var clients = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = clients.submit(() -> raw("POST", drugs(profileId), body, token));
      var second = clients.submit(() -> raw("POST", drugs(profileId), body, token));
      var a = first.get(10, TimeUnit.SECONDS); var b = second.get(10, TimeUnit.SECONDS);
      assertThat(a.statusCode()).as(a.body()).isEqualTo(201);
      assertThat(b.statusCode()).as(b.body()).isEqualTo(201);
      assertThat(JSON.readTree(a.body())).isEqualTo(JSON.readTree(b.body()));
    }
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM my_drug WHERE profile_id=?", Integer.class, profileId)).isEqualTo(1);
  }

  @Test
  void resultOwnershipReplacementCancellationAndExpiryAreEnforced() throws Exception {
    var old = clueSearch(clueInput("A", "B"));
    json("GET", resultPath(old), null, foreignToken, 404);
    json("DELETE", resultPath(old), null, foreignToken, 404);
    String otherPath = resultPath(old).replace("/" + profileId + "/", "/" + otherProfileId + "/");
    json("GET", otherPath, null, token, 404);
    json("POST", drugs(otherProfileId), resultSelection(old, code1), token, 404);
    json("POST", cluePath() + "&previousIdentificationId=" + old.path("identificationId").asString(), Map.of("printFrontState", "READABLE"), token, 400);
    assertThat(json("GET", resultPath(old), null, token, 200).path("lifecycle").asString()).isEqualTo("ACTIVE");
    var replacement = json("POST", cluePath() + "&previousIdentificationId=" + old.path("identificationId").asString(),
        clueInput("ZZ", null), token, 200);
    assertThat(replacement.path("status").asString()).isEqualTo("NOT_FOUND");
    var replaced = json("GET", resultPath(old), null, token, 200);
    assertThat(replaced.path("lifecycle").asString()).isEqualTo("REPLACED");
    assertThat(replaced.path("replacementIdentificationId")).isEqualTo(replacement.path("identificationId"));
    json("POST", drugs(profileId), resultSelection(old, code1), token, 409);
    json("DELETE", resultPath(replacement), null, token, 204);
    json("DELETE", resultPath(replacement), null, token, 204);
    assertThat(json("GET", resultPath(replacement), null, token, 200).path("lifecycle").asString()).isEqualTo("CANCELLED");
    json("POST", drugs(profileId), resultSelection(replacement, code1), token, 409);
    var expires = clueSearch(clueInput("A", "B"));
    jdbc.update("UPDATE pill_identification_session SET created_at=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 31 MINUTE), expires_at=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 SECOND) WHERE identification_id=?",
        expires.path("identificationId").asString());
    json("GET", resultPath(expires), null, token, 410);
    json("POST", drugs(profileId), resultSelection(expires, code1), token, 410);
    sessions.removeExpired();
    json("GET", resultPath(expires), null, token, 404);
  }

  @Test
  void concurrentReplacementAllowsOneNewActiveResult() throws Exception {
    var old = clueSearch(clueInput("A", "B"));
    String path = cluePath() + "&previousIdentificationId=" + old.path("identificationId").asString();
    try (var clients = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = clients.submit(() -> raw("POST", path, clueInput("A", "B"), token));
      var second = clients.submit(() -> raw("POST", path, clueInput("A", "B"), token));
      assertThat(List.of(first.get(10, TimeUnit.SECONDS).statusCode(), second.get(10, TimeUnit.SECONDS).statusCode()))
          .containsExactlyInAnyOrder(200, 409);
    }
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pill_identification_session WHERE profile_id=? AND lifecycle='ACTIVE'", Integer.class, profileId)).isEqualTo(1);
  }

  @Test
  void photoDetailsAndResultRestoreShareEvidenceAndKeepTheOriginalTtl() throws Exception {
    vision = pill("", "B", "A");
    var upload = upload("PILL", token, png(), "front", "back");
    assertThat(upload.headers().firstValue("X-Pill-Identification-Version")).contains("1");
    var result = JSON.readTree(upload.body());
    assertThat(result.path("status").asString()).isEqualTo("OK");
    assertThat(result.path("registrationSource").asString()).isEqualTo("PILL_PHOTO");
    assertThat(result.path("candidates").path(0).path("orientation").asString()).isEqualTo("SWAPPED");
    assertThat(json("GET", resultPath(result), null, token, 200)).isEqualTo(result);
    long photo = result.path("photoId").asLong();
    assertThat(json("GET", "/api/photos/" + photo + "/candidates", null, token, 200)).isEqualTo(result);
    var uploadedAt = jdbc.queryForObject("SELECT uploaded_at FROM photo WHERE photo_id=?", java.sql.Timestamp.class, photo).toLocalDateTime();
    assertThat(OffsetDateTime.parse(result.path("expiresAt").asString()).toLocalDateTime())
        .isBeforeOrEqualTo(uploadedAt.plusMinutes(30));
    var body = resultSelection(result, code1);
    var saved = json("POST", drugs(profileId), body, token, 201);
    assertNoPhotos();
    assertThat(json("POST", drugs(profileId), body, token, 201)).isEqualTo(saved);
    assertThat(visionRequests).hasSize(1);
  }

  @Test
  void insufficientPhotoKeepsOnlyStructuredResultAndCanBeCorrectedWithoutVision() throws Exception {
    vision = pill("", "", "");
    var failed = identify("PILL", token, 200, "front", "back");
    assertThat(failed.path("status").asString()).isEqualTo("UNREADABLE");
    assertThat(failed.path("photoId").isNull()).isTrue();
    assertNoPhotos();
    assertThat(json("GET", resultPath(failed), null, token, 200)).isEqualTo(failed);
    var corrected = json("POST", cluePath() + "&previousIdentificationId=" + failed.path("identificationId").asString(),
        clueInput("A", "B"), token, 200);
    assertThat(corrected.path("origin").asString()).isEqualTo("CLUES");
    json("POST", drugs(profileId), resultSelection(corrected, code1), token, 201);
    assertThat(visionRequests).hasSize(1);
  }

  @Test
  void swaggerDescribesTheNewResultAndBrowserCanReadItsVersion() throws Exception {
    var docs = json("GET", "/v3/api-docs", null, null, 200);
    var path = docs.path("paths").path("/api/profiles/{profileId}/pill-identifications/{identificationId}");
    assertThat(path.has("get")).isTrue(); assertThat(path.has("delete")).isTrue();
    var schema = docs.path("components").path("schemas").path("PhotoIdentificationResponse");
    assertThat(schema.path("properties").has("identificationId")).as(schema.toString()).isTrue();
    assertThat(schema.path("properties").has("totalCandidateCount")).isTrue();
    assertThat(docs.path("components").path("schemas").path("PillIdentification").path("properties").has("printFrontState")).isTrue();
    var paths = docs.path("paths");
    var clueResponse = paths.path("/api/images/identify").path("post").path("responses")
        .path("200").path("content").path("application/json").path("schema");
    assertThat(clueResponse.path("oneOf").size()).isEqualTo(2);
    assertThat(clueResponse.findValuesAsString("$ref")).contains(
        "#/components/schemas/PhotoIdentificationResponse", "#/components/schemas/PillIdentificationCandidate");
    assertThat(clueResponse.findValuesAsString("type")).contains("array");
    var photoResponse = paths.path("/api/images/pill-photo/identify").path("post").path("responses")
        .path("200").path("content").path("application/json").path("schema");
    assertThat(photoResponse.findValuesAsString("$ref")).contains(
        "#/components/schemas/PhotoIdentificationResponse", "#/components/schemas/PillPhotoIdentificationResponse");
    var resultResponse = path.path("get").path("responses").path("200").path("content").path("application/json").path("schema");
    assertThat(resultResponse.findValuesAsString("$ref")).contains(
        "#/components/schemas/PhotoIdentificationResponse", "#/components/schemas/PillIdentificationTerminal");
    var terminal = docs.path("components").path("schemas").path("PillIdentificationTerminal");
    assertThat(terminal.path("properties").has("replacementIdentificationId")).isTrue();
    assertThat(terminal.path("properties").path("registrationReceipt").path("$ref").asString())
        .isEqualTo("#/components/schemas/PillRegistrationReceipt");
    var receipt = docs.path("components").path("schemas").path("PillRegistrationReceipt");
    assertThat(receipt.path("properties").has("httpStatus")).isTrue();
    assertThat(receipt.path("properties").path("body").path("$ref").asString())
        .isEqualTo("#/components/schemas/PhotoDrugSelectionResult");
    var registration = paths.path("/api/profiles/{profileId}/drugs").path("post").path("responses");
    assertThat(registration.has("201")).isTrue(); assertThat(registration.has("200")).isTrue();
    var createdSchema = registration.path("201").path("content").path("application/json").path("schema");
    assertThat(createdSchema.findValuesAsString("$ref")).contains(
        "#/components/schemas/PhotoDrugSelectionResult", "#/components/schemas/MyDrugDetailResponse");
    assertThat(createdSchema.findValuesAsString("type")).contains("array");
    for (var deletePath : List.of("/api/profiles/{profileId}/pill-identifications/{identificationId}", "/api/photos/{photoId}")) {
      var responses = paths.path(deletePath).path("delete").path("responses");
      assertThat(responses.has("204")).isTrue(); assertThat(responses.has("200")).isFalse();
      assertThat(responses.path("204").has("content")).isFalse();
    }
    var request = HttpRequest.newBuilder(URI.create(url(cluePath())))
        .header("Authorization", "Bearer " + token).header("Origin", "http://localhost:5173")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(clueInput("A", "B")))).build();
    var response = browser.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    assertThat(response.headers().firstValue("Access-Control-Expose-Headers").orElse("")).contains("X-Pill-Identification-Version");
  }

  @Test
  void auxiliaryPhotoDetailsUsesCanonicalResultWhileDefaultKeepsLegacyEnvelope() throws Exception {
    vision = pill("", "A", "B");
    var detail = uploadTo("/api/images/pill-photo/identify?view=details&profileId=" + profileId,
        token, png(), Duration.ofSeconds(20), "front", "back");
    assertThat(detail.statusCode()).as(detail.body()).isEqualTo(200);
    var result = JSON.readTree(detail.body());
    assertThat(result.path("contractVersion").asInt()).isEqualTo(1);
    assertThat(json("GET", resultPath(result), null, token, 200)).isEqualTo(result);
    json("DELETE", resultPath(result), null, token, 204);
    var legacy = uploadTo("/api/images/pill-photo/identify", token, png(), Duration.ofSeconds(20), "front", "back");
    assertThat(legacy.statusCode()).as(legacy.body()).isEqualTo(200);
    var body = JSON.readTree(legacy.body());
    assertThat(body.has("extracted")).isTrue(); assertThat(body.has("identificationId")).isFalse();
    assertThat(body.path("candidates").path(0).path("similarityScore").isNull()).isTrue();
    assertNoPhotos();
  }

  @Test
  void failedReplacementAndOversizedSnapshotPreserveThePriorActiveResult() throws Exception {
    var old = clueSearch(clueInput("A", "B"));
    visionStatus = 503;
    var failed = uploadTo("/api/profiles/" + profileId + "/photo-identifications?kind=PILL&previousIdentificationId="
        + old.path("identificationId").asString(), token, png(), Duration.ofSeconds(20), "front", "back");
    assertThat(failed.statusCode()).as(failed.body()).isEqualTo(503);
    assertNoPhotos();
    assertThat(json("GET", resultPath(old), null, token, 200).path("lifecycle").asString()).isEqualTo("ACTIVE");
    officialRows = List.of(row(code1, "공식약", "A" + " ".repeat(70000) + "A", "B")); catalog.refresh();
    var error = json("POST", cluePath() + "&previousIdentificationId=" + old.path("identificationId").asString(), clueInput("AA", "B"), token, 503);
    assertThat(error.path("code").asString()).isEqualTo("IDENTIFICATION_RESULT_UNAVAILABLE");
    assertThat(json("GET", resultPath(old), null, token, 200).path("lifecycle").asString()).isEqualTo("ACTIVE");
  }

  @Test
  void schemaPreflightBlocksPaidPillOcrButPreservesPrescriptionAndNameSearch() throws Exception {
    jdbc.execute("RENAME TABLE pill_identification_session TO pill_identification_session_unavailable_test");
    try {
      var error = identify("PILL", token, 503, "front", "back");
      assertThat(error.path("code").asString()).isEqualTo("IDENTIFICATION_SCHEMA_NOT_READY");
      assertThat(visionRequests).isEmpty(); assertNoPhotos();
      long id = identify("PRESCRIPTION", token, 200, "image").path("photoId").asLong();
      json("POST", drugs(profileId), selection(id, "PRESCRIPTION_PHOTO", code1), token, 201);
      json("POST", drugs(profileId), Map.of("source", "SEARCH", "items", List.of(Map.of("itemSeq", code2))), token, 201);
    } finally {
      jdbc.execute("RENAME TABLE pill_identification_session_unavailable_test TO pill_identification_session");
    }
  }

  String cluePath() { return "/api/images/identify?view=details&profileId=" + profileId; }
  JsonNode clueSearch(Object input) throws Exception { return json("POST", cluePath(), input, token, 200); }
  static Map<String, Object> clueInput(String front, String back) {
    var input = new LinkedHashMap<String, Object>();
    if (front != null) input.put("printFront", front);
    if (back != null) input.put("printBack", back);
    input.put("shape", "원형"); input.put("colorClass1", "흰색");
    return input;
  }
  String resultPath(JsonNode result) {
    return "/api/profiles/" + profileId + "/pill-identifications/" + result.path("identificationId").asString();
  }
  static Map<String, Object> resultSelection(JsonNode result, String code) {
    var input = new LinkedHashMap<String, Object>();
    input.put("identificationId", result.path("identificationId").asString());
    input.put("source", result.path("registrationSource").asString());
    if (!result.path("photoId").isNull()) input.put("photoId", result.path("photoId").asLong());
    input.put("items", List.of(Map.of("itemSeq", code)));
    return input;
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
    return upload(kind, auth, image, Duration.ofSeconds(20), parts);
  }

  HttpResponse<String> upload(String kind, String auth, byte[] image, Duration timeout, String... parts)
      throws Exception {
    return uploadTo("/api/profiles/" + profileId + "/photo-identifications?kind=" + kind, auth, image, timeout, parts);
  }

  HttpResponse<String> uploadTo(String path, String auth, byte[] image, Duration timeout, String... parts)
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
                    url(path)))
            .timeout(timeout)
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
    m.put("shape", "원형");
    m.put("colorClass1", "흰색");
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
          if (t.visionDelayMs > 0) Thread.sleep(t.visionDelayMs);
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
          if (t.permitMetadata && !t.officialRows.isEmpty()) {
            detail.putAll(t.officialRows.getFirst());
            // 실제 목록/상세는 전문·일반 필드명이 서로 다름.
            Object type = detail.remove("SPCLTY_PBLC");
            if (type != null) detail.put("ETC_OTC_CODE", type);
          }
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
                  t.durWarning && path.contains("getSpcifyAgrdeTaboo")
                      ? Map.of("totalCount", 1, "items", List.of(Map.of("ITEM_SEQ", t.code1, "TYPE_NAME", "연령금기", "PROHBT_CONTENT", "120세 미만", "CHANGE_DATE", "20261020")))
                      : Map.of("totalCount", 0, "items", List.of())));
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
