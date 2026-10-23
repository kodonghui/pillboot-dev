package org.semiproject.pillbadeumserver.image.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.semiproject.pillbadeumserver.common.config.TimeZoneConfig;
import org.semiproject.pillbadeumserver.common.exception.*;
import org.semiproject.pillbadeumserver.common.security.SecurityUtil;
import org.semiproject.pillbadeumserver.drug.domain.DrugSource;
import org.semiproject.pillbadeumserver.drug.service.ProfileAccessChecker;
import org.semiproject.pillbadeumserver.image.domain.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** REG-007: 사진 없는 보완 검색도 30분 복원함. 기존 JDBC·사진 저장/정리 서비스를 재사용함. */
@Service
public class PillIdentificationSessions {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final ImageKeywordRegistrationService photos;
  private final ProfileAccessChecker access;
  private final TemporaryPhotoCleanupService cleanup;
  @Value("${pill-identification.enabled:true}")
  private boolean enabled = true;

  public PillIdentificationSessions(JdbcTemplate jdbc, ObjectMapper json,
      ImageKeywordRegistrationService photos, ProfileAccessChecker access,
      TemporaryPhotoCleanupService cleanup) {
    this.jdbc = jdbc; this.json = json; this.photos = photos; this.access = access; this.cleanup = cleanup;
  }

  /** 유료 판독 전 권한·추가 SQL·대체 대상을 확인하며 외부 호출 중에는 잠금을 잡지 않음. */
  public void preflight(long profileId, String previousId) {
    access.checkOwner(profileId);
    requireReady();
    if (previousId != null) checkActive(owned(profileId, previousId, false));
  }
  public void requireReady() {
    if (!enabled) throw failure(ErrorCode.IDENTIFICATION_DISABLED, "알약 식별은 잠시 사용할 수 없습니다. 이름 검색을 이용해 주세요.");
    try {
      jdbc.queryForList("SELECT identification_id,member_id,profile_id,photo_id,origin,lifecycle,policy_version,"
          + "snapshot,selected_item_seq,registration_receipt,replacement_id,created_at,expires_at"
          + " FROM pill_identification_session WHERE 1=0");
    } catch (DataAccessException missing) {
      throw failure(ErrorCode.IDENTIFICATION_SCHEMA_NOT_READY, "알약 식별 DB 패치가 필요합니다. 이름 검색을 이용해 주세요.");
    }
  }

  @Transactional
  public PhotoIdentificationResponse create(long profileId, String origin, PillIdentification clues,
      PhotoCandidateLookup.PillResult result, Long photoId, List<Long> photoIds, String previousId) {
    requireReady();
    photos.lockProfile(profileId);
    Session previous = previousId == null ? null : owned(profileId, previousId, false);
    // 회원→프로필→사진→결과 순서임. 같은 프로필의 교체/등록은 위 잠금으로 직렬화함.
    var photoLocks = new TreeSet<Long>();
    if (photoId != null) photoLocks.add(photoId);
    if (previous != null && previous.photoId() != null) photoLocks.add(previous.photoId());
    for (long id : photoLocks) photos.owned(id, true);
    if (previous != null) { previous = owned(profileId, previousId, true); checkActive(previous); }
    LocalDateTime created = now(), expires = created.plusMinutes(30);
    if (photoId != null) {
      var photo = photos.owned(photoId, false);
      if (!Objects.equals(photo.profileId(), profileId) || photo.kind() != ImagePhotoKind.PILL
          || !"IDENTIFIED".equals(photo.status()))
        throw failure(ErrorCode.INVALID_INPUT, "사진의 프로필·종류·상태를 확인해 주세요.");
      if (photo.uploadedAt().plusMinutes(30).isBefore(expires)) expires = photo.uploadedAt().plusMinutes(30);
      if (!expires.isAfter(created)) throw expired();
    }
    String id = UUID.randomUUID().toString();
    var registered = registered(profileId);
    var candidates = result.candidates().stream().map(c -> view(c, registered)).toList();
    var groups = result.missingFields().isEmpty()
        ? List.of(new PhotoIdentificationResponse.DrugGroup(1, "알약 식별 후보", result.lookupStatus(), candidates))
        : List.<PhotoIdentificationResponse.DrugGroup>of();
    var details = new PillIdentificationDetails(1, id, "ACTIVE", origin, source(origin), at(expires),
        reason(result.status(), result.totalCandidateCount(), candidates),
        result.totalCandidateCount(), candidates.size(), 5, result.totalCandidateCount() > candidates.size(),
        result.hasTiedCandidates(), result.totalCandidateCount() > 5,
        selectable(result.status(), result.totalCandidateCount(), candidates), result.missingFields(),
        actions(result.status(), result.totalCandidateCount(), candidates));
    Boolean matched = !candidates.isEmpty() && candidates.stream().allMatch(c -> Boolean.TRUE.equals(c.printMatched())) ? Boolean.TRUE : null;
    var response = new PhotoIdentificationResponse(ImagePhotoKind.PILL, photoId,
        photoId == null ? null : List.copyOf(photoIds), result.status(), clues, matched, groups, candidates, details);
    var snapshot = json.createObjectNode();
    snapshot.set("response", json.valueToTree(response));
    snapshot.set("candidates", json.valueToTree(result.candidates()));
    snapshot.put("catalogVersion", result.catalogVersion()); snapshot.put("policyVersion", 1);
    snapshot.put("createdAt", at(created).toString());
    String serialized = json.writeValueAsString(snapshot);
    if (serialized.getBytes(StandardCharsets.UTF_8).length > 65_536)
      throw failure(ErrorCode.IDENTIFICATION_RESULT_UNAVAILABLE, "식별 결과를 보관하지 못했습니다. 다시 요청해 주세요.");
    try {
      jdbc.update("INSERT INTO pill_identification_session"
          + "(identification_id,member_id,profile_id,photo_id,origin,lifecycle,policy_version,snapshot,created_at,expires_at)"
          + " VALUES(?,?,?,?,?,'ACTIVE',1,?,?,?)", id, SecurityUtil.getCurrentMemberId(),
          profileId, photoId, origin, serialized, created, expires);
      if (previous != null) {
        jdbc.update("UPDATE pill_identification_session SET lifecycle='REPLACED',snapshot=NULL,replacement_id=?"
            + " WHERE identification_id=?", id, previous.id());
        afterCommitCleanup(previous.photoId());
      }
    } catch (DataAccessException failed) {
      throw failure(ErrorCode.IDENTIFICATION_RESULT_UNAVAILABLE, "식별 결과를 보관하지 못했습니다. 다시 요청해 주세요.");
    }
    return response;
  }

  @Transactional(readOnly = true)
  public JsonNode read(long profileId, String id) {
    requireReady();
    Session row = owned(profileId, id, false);
    checkFresh(row);
    if (!"ACTIVE".equals(row.lifecycle())) return terminal(row);
    if (row.policy() != 1) throw replaced();
    if ("PHOTO".equals(row.origin())) {
      if (row.photoId() == null && "OK".equals(response(row).path("status").asString())) throw expired();
      if (row.photoId() != null) {
        var photo = photos.owned(row.photoId(), false);
        if (!Objects.equals(photo.profileId(), profileId) || !photo.uploadedAt().plusMinutes(30).isAfter(now())) throw expired();
      }
    }
    var result = response(row);
    var registered = registered(profileId);
    refreshRegistered(result.path("candidates"), registered);
    for (var group : result.path("drugs")) refreshRegistered(group.path("candidates"), registered);
    var candidates = json.convertValue(result.path("candidates"),
        new tools.jackson.core.type.TypeReference<List<PhotoIdentificationResponse.Candidate>>() {});
    String status = result.path("status").asString();
    int total = result.path("totalCandidateCount").asInt();
    result.put("selectionAllowed", selectable(status, total, candidates));
    String reason = reason(status, total, candidates);
    if (reason == null) result.putNull("reason"); else result.put("reason", reason);
    result.set("nextActions", json.valueToTree(actions(status, total, candidates)));
    return result;
  }
  @Transactional(readOnly = true)
  public JsonNode readPhoto(long photoId) {
    var photo = photos.owned(photoId, false);
    requireReady();
    var rows = jdbc.query("SELECT * FROM pill_identification_session WHERE photo_id=?"
        + " AND member_id=? AND profile_id=?", PillIdentificationSessions::row,
        photo.id(), SecurityUtil.getCurrentMemberId(), photo.profileId());
    if (rows.isEmpty()) throw replaced();
    return read(photo.profileId(), rows.getFirst().id());
  }

  @Transactional
  public Long cancel(long profileId, String id) {
    requireReady();
    Session peek = owned(profileId, id, false);
    photos.lockProfile(profileId);
    if (peek.photoId() != null) photos.owned(peek.photoId(), true);
    Session row = owned(profileId, id, true);
    checkFresh(row);
    if (!"ACTIVE".equals(row.lifecycle())) return null;
    jdbc.update("UPDATE pill_identification_session SET lifecycle='CANCELLED',snapshot=NULL WHERE identification_id=?", id);
    return row.photoId();
  }

  /** 기존 사진 DELETE도 연결 결과의 저장 가능 상태를 종료함. */
  @Transactional
  public void cancelPhoto(long photoId) {
    if (!tablePresent()) return;
    var photo = photos.owned(photoId, false);
    photos.lockProfile(photo.profileId());
    photos.owned(photo.id(), true);
    var rows = jdbc.query("SELECT * FROM pill_identification_session WHERE photo_id=? FOR UPDATE",
        PillIdentificationSessions::row, photo.id());
    for (var row : rows) if ("ACTIVE".equals(row.lifecycle()))
      jdbc.update("UPDATE pill_identification_session SET lifecycle='CANCELLED',snapshot=NULL WHERE identification_id=?", row.id());
  }

  public record Confirmation(String identificationId, PhotoDrugSelectionRequest.Result replay) {}

  /** 기존 확인 트랜잭션 안에서 후보/만료/출처를 확인함. 영수증도 같은 커밋에 저장함. */
  public Confirmation beforeConfirm(long profileId, PhotoDrugSelectionRequest request, List<String> codes) {
    requireReady();
    String id = request.identificationId();
    if (id == null) {
      if (request.photoId() == null) throw failure(ErrorCode.INVALID_INPUT, "식별 결과 ID가 필요합니다.");
      var photo = photos.owned(request.photoId(), true);
      if (photo.kind() != ImagePhotoKind.PILL || !Objects.equals(photo.profileId(), profileId))
        throw failure(ErrorCode.INVALID_INPUT, "사진의 프로필·종류를 확인해 주세요.");
      var found = jdbc.queryForList("SELECT identification_id FROM pill_identification_session"
          + " WHERE photo_id=? AND member_id=? AND profile_id=?", String.class, photo.id(),
          SecurityUtil.getCurrentMemberId(), profileId);
      if (found.isEmpty()) throw replaced();
      id = found.getFirst();
    }
    Session peek = owned(profileId, id, false);
    if (peek.photoId() != null && "ACTIVE".equals(peek.lifecycle())) photos.owned(peek.photoId(), true);
    Session row = owned(profileId, id, true);
    checkFresh(row);
    if (codes.size() != 1) throw failure(ErrorCode.INVALID_INPUT, "알약 후보는 한 개만 선택해 주세요.");
    if (request.source() != source(row.origin())) throw failure(ErrorCode.INVALID_INPUT, "식별 결과의 등록 출처를 확인해 주세요.");
    if ("CONSUMED".equals(row.lifecycle())) {
      if (!codes.getFirst().equals(row.selectedCode()))
        throw failure(ErrorCode.IDENTIFICATION_SELECTION_CONFLICT, "이 결과로 이미 다른 약을 저장했습니다.");
      return new Confirmation(id, json.treeToValue(json.readTree(row.receipt()).path("body"), PhotoDrugSelectionRequest.Result.class));
    }
    checkActive(row);
    ObjectNode result = response(row);
    if (!"OK".equals(result.path("status").asString()) || result.path("totalCandidateCount").asInt() > 5)
      throw failure(ErrorCode.IDENTIFICATION_NOT_SELECTABLE, "정보를 보완한 뒤 후보를 확인해 주세요.");
    if ("PHOTO".equals(row.origin())) {
      if (row.photoId() == null) throw expired();
      var photo = photos.owned(row.photoId(), false);
      if (!photo.uploadedAt().plusMinutes(30).isAfter(now())) throw expired();
      if (!Objects.equals(photo.id(), request.photoId()))
        throw failure(ErrorCode.INVALID_INPUT, "식별 결과의 사진을 확인해 주세요.");
    } else if (request.photoId() != null) throw failure(ErrorCode.INVALID_INPUT, "직접 입력 결과에는 사진 ID를 보내지 마세요.");
    PhotoCandidateData candidate = null;
    for (var value : json.readTree(row.snapshot()).path("candidates"))
      if (codes.getFirst().equals(value.path("itemSeq").asString())) candidate = json.treeToValue(value, PhotoCandidateData.class);
    if (candidate == null) throw failure(ErrorCode.INVALID_INPUT, "반환된 후보에 없는 약은 저장할 수 없습니다.");
    photos.cache(candidate, "GRAIN");
    return new Confirmation(id, null);
  }
  public void consumed(Confirmation confirmation, String code, PhotoDrugSelectionRequest.Result result) {
    var receipt = json.createObjectNode();
    receipt.put("httpStatus", result.saved().isEmpty() ? 200 : 201);
    receipt.set("body", json.valueToTree(result));
    jdbc.update("UPDATE pill_identification_session SET lifecycle='CONSUMED',snapshot=NULL,"
        + "selected_item_seq=?,registration_receipt=? WHERE identification_id=?",
        code, json.writeValueAsString(receipt), confirmation.identificationId());
  }

  /** 기존 사진 정리 주기에서만 호출함. 새 스케줄러를 두지 않음. */
  @Transactional
  public void removeExpired() {
    if (!tablePresent()) return;
    var expired = jdbc.query("SELECT * FROM pill_identification_session WHERE expires_at<=? ORDER BY expires_at LIMIT 100",
        PillIdentificationSessions::row, now());
    for (var row : expired) {
      jdbc.queryForList("SELECT member_id FROM member WHERE member_id=? FOR UPDATE", Long.class, row.memberId());
      jdbc.queryForList("SELECT profile_id FROM profile WHERE profile_id=? FOR UPDATE", Long.class, row.profileId());
      if (row.photoId() != null) jdbc.queryForList("SELECT photo_id FROM photo WHERE photo_id=? FOR UPDATE", Long.class, row.photoId());
      jdbc.queryForList("SELECT identification_id FROM pill_identification_session WHERE identification_id=? FOR UPDATE", String.class, row.id());
      jdbc.update("DELETE FROM pill_identification_session WHERE identification_id=? AND expires_at<=?", row.id(), now());
    }
  }
  private boolean tablePresent() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()"
        + " AND table_name='pill_identification_session'", Integer.class) == 1;
  }
  private Session owned(long profileId, String id, boolean lock) {
    try { if (id == null || !UUID.fromString(id).toString().equalsIgnoreCase(id)) throw new IllegalArgumentException(); }
    catch (IllegalArgumentException e) { throw failure(ErrorCode.INVALID_INPUT, "식별 결과 ID를 확인해 주세요."); }
    var rows = jdbc.query("SELECT * FROM pill_identification_session"
        + " WHERE identification_id=? AND member_id=? AND profile_id=?" + (lock ? " FOR UPDATE" : ""),
        PillIdentificationSessions::row, id, SecurityUtil.getCurrentMemberId(), profileId);
    if (rows.isEmpty()) throw new NotFoundException("식별 결과를 찾을 수 없습니다.");
    return rows.getFirst();
  }
  private void checkActive(Session row) {
    checkFresh(row);
    if ("REPLACED".equals(row.lifecycle()) || row.policy() != 1) throw replaced();
    if (!"ACTIVE".equals(row.lifecycle())) throw failure(ErrorCode.IDENTIFICATION_NOT_SELECTABLE, "사용할 수 없는 식별 결과입니다.");
  }
  private void checkFresh(Session row) { if (!row.expires().isAfter(now())) throw expired(); }
  private ObjectNode response(Session row) {
    if (row.snapshot() == null) throw failure(ErrorCode.IDENTIFICATION_NOT_SELECTABLE, "식별 결과를 사용할 수 없습니다.");
    return (ObjectNode) json.readTree(row.snapshot()).path("response").deepCopy();
  }
  private JsonNode terminal(Session row) {
    var result = json.createObjectNode();
    result.put("contractVersion", 1); result.put("identificationId", row.id());
    result.put("lifecycle", row.lifecycle()); result.put("origin", row.origin());
    result.put("registrationSource", source(row.origin()).name()); result.put("expiresAt", at(row.expires()).toString());
    if ("REPLACED".equals(row.lifecycle())) {
      result.put("replacementIdentificationId", row.replacement()); result.set("nextActions", json.valueToTree(List.of()));
    } else if ("CONSUMED".equals(row.lifecycle())) {
      result.set("registrationReceipt", json.readTree(row.receipt()));
      result.set("nextActions", json.valueToTree(List.of("VIEW_MY_DRUGS")));
    } else result.set("nextActions", json.valueToTree(List.of("EDIT_CLUES", "RETAKE", "SEARCH_BY_NAME")));
    return result;
  }
  private Set<String> registered(long profileId) {
    return new HashSet<>(jdbc.queryForList("SELECT item_seq FROM my_drug WHERE profile_id=? AND item_seq IS NOT NULL", String.class, profileId));
  }
  private static void refreshRegistered(JsonNode candidates, Set<String> registered) {
    for (var candidate : candidates) ((ObjectNode) candidate).put("alreadyRegistered", registered.contains(candidate.path("itemSeq").asString()));
  }
  private static PhotoIdentificationResponse.Candidate view(PhotoCandidateData c, Set<String> registered) {
    return new PhotoIdentificationResponse.Candidate(c.itemSeq(), c.name(), c.maker(), c.etcOtc(),
        c.imageUrl(), c.cancelled(), registered.contains(c.itemSeq()), c.printMatched(),
        c.printFront(), c.printBack(), c.shape(), c.color(), c.orientation(), c.evidenceRank(), c.matchDetails(), c.material());
  }
  private static boolean selectable(String status, int total, List<PhotoIdentificationResponse.Candidate> candidates) {
    return "OK".equals(status) && total > 0 && total <= 5 && candidates.stream().anyMatch(c -> !c.alreadyRegistered());
  }
  private static String reason(String status, int total, List<PhotoIdentificationResponse.Candidate> candidates) {
    if ("UNREADABLE".equals(status)) return "MISSING_REQUIRED_CLUES";
    if (total == 0) return "NO_MATCH";
    if (total > 5) return "TOO_MANY_CANDIDATES";
    if (candidates.stream().allMatch(PhotoIdentificationResponse.Candidate::alreadyRegistered)) return "ALL_ALREADY_REGISTERED";
    return null;
  }
  private static List<String> actions(String status, int total, List<PhotoIdentificationResponse.Candidate> candidates) {
    if ("OK".equals(status) && total > 0 && total <= 5) return selectable(status, total, candidates)
        ? List.of("SELECT_CANDIDATE", "EDIT_CLUES", "RETAKE", "SEARCH_BY_NAME") : List.of("VIEW_MY_DRUGS");
    return List.of("EDIT_CLUES", "RETAKE", "SEARCH_BY_NAME");
  }
  private void afterCommitCleanup(Long photoId) {
    if (photoId == null) return;
    var names = photos.members(photoId).stream().map(ImageKeywordRegistrationService.Photo::path).toList();
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override public void afterCommit() {
        try { if (cleanup.deleteNow(names).failed() == 0) photos.deleteRows(photoId); }
        catch (RuntimeException ignored) { /* 기존 TTL 정리에서 재시도함. 관찰값·ID를 로그에 남기지 않음. */ }
      }
    });
  }
  private static DrugSource source(String origin) { return "PHOTO".equals(origin) ? DrugSource.PILL_PHOTO : DrugSource.PILL_CLUES; }
  private static LocalDateTime now() { return LocalDateTime.now(TimeZoneConfig.ZONE).truncatedTo(ChronoUnit.SECONDS); }
  private static OffsetDateTime at(LocalDateTime value) { return value.atZone(TimeZoneConfig.ZONE).toOffsetDateTime(); }
  private static BusinessException failure(ErrorCode code, String message) { return new BusinessException(code, message); }
  private static BusinessException expired() { return failure(ErrorCode.IDENTIFICATION_EXPIRED, "시간이 지난 결과입니다. 다시 찍거나 정보를 입력해 주세요."); }
  private static BusinessException replaced() { return failure(ErrorCode.IDENTIFICATION_REPLACED, "이전 결과입니다. 새로 식별해 주세요."); }
  private record Session(String id, long memberId, long profileId, Long photoId, String origin,
      String lifecycle, int policy, String snapshot, String selectedCode, String receipt, String replacement, LocalDateTime expires) {}
  private static Session row(ResultSet rs, int index) throws SQLException {
    return new Session(rs.getString("identification_id"), rs.getLong("member_id"), rs.getLong("profile_id"),
        rs.getObject("photo_id", Long.class), rs.getString("origin"), rs.getString("lifecycle"),
        rs.getInt("policy_version"), rs.getString("snapshot"), rs.getString("selected_item_seq"),
        rs.getString("registration_receipt"), rs.getString("replacement_id"), rs.getObject("expires_at", LocalDateTime.class));
  }
}
