package org.semiproject.pillbadeumserver.image.service;

import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.*;
import org.semiproject.pillbadeumserver.common.config.TimeZoneConfig;
import org.semiproject.pillbadeumserver.common.exception.*;
import org.semiproject.pillbadeumserver.drug.domain.*;
import org.semiproject.pillbadeumserver.image.domain.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** REG-005: 공식 후보/소유권/중복을 같은 프로필 잠금 아래 확인한다. 네트워크·파일 IO는 수행하지 않는다. */
@Service
public class ImageDrugConfirmationService {
  private final JdbcTemplate jdbc;
  private final ImageKeywordRegistrationService photos;
  private final ApplicationEventPublisher events;
  private final PillIdentificationSessions sessions;

  public ImageDrugConfirmationService(
      JdbcTemplate jdbc, ImageKeywordRegistrationService photos, ApplicationEventPublisher events,
      PillIdentificationSessions sessions) {
    this.jdbc = jdbc;
    this.photos = photos;
    this.events = events;
    this.sessions = sessions;
  }

  @Transactional
  public PhotoDrugSelectionRequest.Result confirm(
      long profileId, PhotoDrugSelectionRequest request, List<String> codes) {
    photos.lockProfile(profileId);
    boolean pill = request.source() == DrugSource.PILL_PHOTO || request.source() == DrugSource.PILL_CLUES;
    var verified = pill ? sessions.beforeConfirm(profileId, request, codes) : null;
    if (verified != null && verified.replay() != null) return verified.replay();
    var existing = new HashMap<String, PhotoDrugSelectionRequest.Skipped>();
    jdbc.query(
        "SELECT m.my_drug_id,m.item_seq,d.name FROM my_drug m JOIN drug d ON d.item_seq=m.item_seq"
            + " WHERE m.profile_id=?",
        rs -> {
          existing.put(
              rs.getString("item_seq"),
              new PhotoDrugSelectionRequest.Skipped(
                  rs.getLong("my_drug_id"),
                  rs.getString("item_seq"),
                  rs.getString("name"),
                  "ALREADY_REGISTERED"));
        },
        profileId);
    boolean hasNew = codes.stream().anyMatch(c -> !existing.containsKey(c));
    if (!pill && request.photoId() != null && hasNew) {
      var photo = photos.owned(request.photoId(), true);
      ImageKeywordRegistrationService.checkFresh(photo);
      if (!Objects.equals(photo.profileId(), profileId)
          || !"IDENTIFIED".equals(photo.status())
          || photo.kind() == ImagePhotoKind.PILL)
        throw invalid("사진의 프로필·종류·상태를 확인해 주세요.");
      var candidateCards = new LinkedHashMap<String, Set<Integer>>();
      jdbc.query(
          "SELECT item_seq,read_no FROM ocr_candidate WHERE photo_id=? AND lookup_status IN('OK','PARTIAL')"
              + " AND item_seq IS NOT NULL ORDER BY read_no,candidate_rank",
          rs -> {
            candidateCards.computeIfAbsent(rs.getString("item_seq"), ignored -> new LinkedHashSet<>())
                .add(rs.getInt("read_no"));
          }, photo.id());
      var candidates = candidateCards.keySet();
      if (codes.stream().anyMatch(c -> request.sourceOf(c) != DrugSource.SEARCH
          && !existing.containsKey(c) && !candidates.contains(c)))
        throw invalid("사진 후보에 없는 약은 저장할 수 없습니다.");
      // 서로 다른 코드여도 한 카드의 대안 후보를 여러 약으로 확정할 수 없음.
      // 같은 품목이 여러 카드에 겹치는 처방전은 일대일 배정이 가능한지 확인함.
      var assignment = new HashMap<Integer, String>();
      // 보완 검색에서 지정한 자리는 사진 대안과 겹치지 않게 먼저 예약함.
      for (String code : codes) {
        Integer readNo = request.readNoOf(code);
        if (readNo == null) continue;
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM ocr_candidate WHERE photo_id=? AND read_no=?", Integer.class, photo.id(), readNo);
        if (count == null || count == 0 || request.sourceOf(code) != DrugSource.SEARCH
            && !candidateCards.getOrDefault(code, Set.of()).contains(readNo))
          throw invalid("처방전의 약 순서와 후보를 확인해 주세요.");
        assignment.put(readNo, code);
      }
      for (String code : codes) {
        if (request.sourceOf(code) == DrugSource.SEARCH || !candidates.contains(code)) continue;
        if (request.readNoOf(code) != null) continue;
        // 명시한 슬롯은 다른 대안 후보에 재배정하지 않음.
        var available = new LinkedHashMap<String, Set<Integer>>();
        candidateCards.forEach((c, values) -> available.put(c, values.stream()
            .filter(slot -> codes.stream().noneMatch(selected -> Objects.equals(request.readNoOf(selected), slot)))
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))));
        if (!assignCard(code, available, assignment, new HashSet<>()))
          throw invalid("약마다 후보를 하나만 선택해 주세요.");
      }
    }
    var saved = new ArrayList<PhotoDrugSelectionRequest.Saved>();
    var skipped = new ArrayList<PhotoDrugSelectionRequest.Skipped>();
    for (String code : codes) {
      if (existing.containsKey(code)) {
        skipped.add(existing.get(code));
        continue;
      }
      var names = jdbc.queryForList("SELECT name FROM drug WHERE item_seq=?", String.class, code);
      if (names.isEmpty()) throw invalid("공식 약 정보를 확인하지 못했습니다.");
      var key = new GeneratedKeyHolder();
      var now = LocalDateTime.now(TimeZoneConfig.ZONE);
      jdbc.update(
          c -> {
            var s =
                c.prepareStatement(
                    "INSERT INTO"
                        + " my_drug(profile_id,item_seq,dose_per_time,dose_confirmed,source,registered_at,updated_at)"
                        + " VALUES(?,?,1,FALSE,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            s.setLong(1, profileId);
            s.setString(2, code);
            s.setString(3, request.sourceOf(code).name());
            s.setObject(4, now);
            s.setObject(5, now);
            return s;
          },
          key);
      saved.add(
          new PhotoDrugSelectionRequest.Saved(
              Objects.requireNonNull(key.getKey()).longValue(), code, names.getFirst()));
    }
    if (!saved.isEmpty())
      events.publishEvent(
          new DrugRegistered(
              saved.stream().map(PhotoDrugSelectionRequest.Saved::itemSeq).toList()));
    var result = new PhotoDrugSelectionRequest.Result(List.copyOf(saved), List.copyOf(skipped));
    if (verified != null) sessions.consumed(verified, codes.getFirst(), result);
    return result;
  }

  private static BusinessException invalid(String s) {
    return new BusinessException(ErrorCode.INVALID_INPUT, s);
  }

  private static boolean assignCard(String code, Map<String, Set<Integer>> cards,
                                    Map<Integer, String> assigned, Set<Integer> visited) {
    for (int card : cards.getOrDefault(code, Set.of())) {
      if (!visited.add(card)) continue;
      String previous = assigned.get(card);
      if (previous == null || assignCard(previous, cards, assigned, visited)) {
        assigned.put(card, code);
        return true;
      }
    }
    return false;
  }
}
