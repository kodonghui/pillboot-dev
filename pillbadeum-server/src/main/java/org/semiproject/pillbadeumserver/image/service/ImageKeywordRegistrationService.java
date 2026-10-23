package org.semiproject.pillbadeumserver.image.service;

import java.sql.*;
import java.time.*;
import java.util.*;
import org.semiproject.pillbadeumserver.common.config.TimeZoneConfig;
import org.semiproject.pillbadeumserver.common.exception.*;
import org.semiproject.pillbadeumserver.common.security.SecurityUtil;
import org.semiproject.pillbadeumserver.image.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** REG-003~007: 사진/공식 후보만 짧은 트랜잭션으로 저장한다. 외부 API와 파일 IO는 호출자가 수행한다. */
@Service
public class ImageKeywordRegistrationService {
  private final JdbcTemplate jdbc;

  public ImageKeywordRegistrationService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public record Photo(
      long id,
      long memberId,
      Long profileId,
      ImagePhotoKind kind,
      Long frontPhotoId,
      String path,
      String mime,
      String status,
      LocalDateTime uploadedAt) {}

  @Transactional
  public List<Long> uploaded(
      long profileId, ImagePhotoKind kind, List<MultipartFile> images, List<String> names) {
    lockProfile(profileId);
    List<Long> ids = new ArrayList<>();
    var now = LocalDateTime.now(TimeZoneConfig.ZONE);
    for (int i = 0; i < images.size(); i++) {
      var key = new GeneratedKeyHolder();
      final int index = i;
      jdbc.update(
          c -> {
            var s =
                c.prepareStatement(
                    "INSERT INTO"
                        + " photo(member_id,profile_id,kind,side,front_photo_id,temp_path,mime_type,size_bytes,status,uploaded_at)"
                        + " VALUES(?,?,?,?,?,?,?,?,'UPLOADED',?)",
                    Statement.RETURN_GENERATED_KEYS);
            s.setLong(1, SecurityUtil.getCurrentMemberId());
            s.setLong(2, profileId);
            s.setString(3, kind.name());
            s.setString(4, kind == ImagePhotoKind.PILL ? (index == 0 ? "FRONT" : "BACK") : null);
            if (index > 0 && kind == ImagePhotoKind.PILL) s.setLong(5, ids.getFirst());
            else s.setNull(5, Types.BIGINT);
            s.setString(6, names.get(index));
            s.setString(7, mime(names.get(index)));
            s.setLong(8, images.get(index).getSize());
            s.setObject(9, now);
            return s;
          },
          key);
      ids.add(Objects.requireNonNull(key.getKey()).longValue());
    }
    return List.copyOf(ids);
  }

  @Transactional
  public void identified(long photoId, List<PhotoCandidateData.Group> groups) {
    Photo root = owned(photoId, true);
    checkFresh(root);
    if (!"UPLOADED".equals(root.status())) throw invalid("이미 처리된 사진입니다.");
    for (var group : groups) {
      if (group.candidates().isEmpty()) insertCandidate(root.id(), group, null, 1);
      else
        for (int i = 0; i < group.candidates().size(); i++) {
          var candidate = group.candidates().get(i);
          cache(candidate, root.kind() == ImagePhotoKind.PILL ? "GRAIN" : "PERMIT");
          insertCandidate(root.id(), group, candidate, i + 1);
        }
    }
    jdbc.update(
        "UPDATE photo SET status='IDENTIFIED' WHERE photo_id=? OR front_photo_id=?",
        root.id(),
        root.id());
  }

  void cache(PhotoCandidateData c, String source) {
    var enrich = List.of("class_name", "image_url", "print_front", "print_back", "shape", "color", "line_front", "line_back", "form", "material")
        .stream().map(column -> column + "=IF(" + column + " IS NULL OR " + column + "='미확인',COALESCE(VALUES(" + column + ")," + column + ")," + column + ")")
        .collect(java.util.stream.Collectors.joining(","));
    jdbc.update(
        """
        INSERT INTO drug(item_seq,name,maker,etc_otc,class_name,image_url,print_front,print_back,shape,color,line_front,line_back,form,material,synced_at,metadata_source,cancelled)
        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE
        """ + enrich + ",metadata_source=CASE WHEN metadata_source='UNKNOWN' THEN VALUES(metadata_source) WHEN metadata_source=VALUES(metadata_source) THEN metadata_source ELSE 'MIXED' END,synced_at=VALUES(synced_at),cancelled=COALESCE(VALUES(cancelled),cancelled)",
        c.itemSeq(),
        c.name(),
        c.maker(),
        c.etcOtc(),
        c.className(),
        c.imageUrl(),
        c.printFront(),
        c.printBack(),
        c.shape(),
        c.color(),
        c.lineFront(),
        c.lineBack(),
        c.form(),
        org.semiproject.pillbadeumserver.drug.domain.DrugIngredient.forStorage(c.material()),
        LocalDateTime.now(TimeZoneConfig.ZONE), source, c.cancelled());
  }

  private void insertCandidate(
      long photoId, PhotoCandidateData.Group group, PhotoCandidateData c, int rank) {
    jdbc.update(
        "INSERT INTO"
            + " ocr_candidate(photo_id,read_no,read_name,item_seq,candidate_rank,lookup_status,print_matched)"
            + " VALUES(?,?,?,?,?,?,?)",
        photoId,
        group.readNo(),
        group.readName(),
        c == null ? null : c.itemSeq(),
        rank,
        group.lookup(),
        c == null ? null : c.printMatched());
  }

  /** 본인 사진만 조회한다. 뒷면 ID는 대표 앞면의 잠금/수명으로 정규화한다. */
  public Photo owned(long photoId, boolean lock) {
    List<Photo> found =
        jdbc.query(
            "SELECT * FROM photo WHERE photo_id=?" + (lock ? " FOR UPDATE" : ""),
            ImageKeywordRegistrationService::row,
            photoId);
    if (found.isEmpty() || found.getFirst().memberId() != SecurityUtil.getCurrentMemberId())
      throw new NotFoundException("사진을 찾을 수 없습니다.");
    Photo p = found.getFirst();
    if (p.frontPhotoId() != null) return owned(p.frontPhotoId(), lock);
    return p;
  }

  public Photo ownedImage(long photoId) {
    Photo root = owned(photoId, false);
    checkFresh(root);
    if (!"IDENTIFIED".equals(root.status())) throw invalid("아직 확인할 수 없는 사진입니다.");
    return members(root.id()).stream()
        .filter(p -> p.id() == photoId)
        .findFirst()
        .orElseThrow(() -> new NotFoundException("사진을 찾을 수 없습니다."));
  }

  public List<Photo> members(long rootId) {
    return jdbc.query(
        "SELECT * FROM photo WHERE photo_id=? OR front_photo_id=? ORDER BY photo_id",
        ImageKeywordRegistrationService::row,
        rootId,
        rootId);
  }

  @Transactional(readOnly = true)
  public PhotoIdentificationResponse view(long photoId) {
    Photo p = owned(photoId, false);
    checkFresh(p);
    if (!"IDENTIFIED".equals(p.status())) throw invalid("식별이 완료되지 않은 사진입니다.");
    var registered =
        new HashSet<>(
            jdbc.queryForList(
                "SELECT item_seq FROM my_drug WHERE profile_id=? AND item_seq IS NOT NULL",
                String.class,
                p.profileId()));
    var values =
        jdbc.queryForList(
            "SELECT"
                + " c.*,d.name,d.maker,d.etc_otc,d.image_url,d.print_front,d.print_back,d.shape,d.color,d.material,d.cancelled"
                + " FROM ocr_candidate c LEFT JOIN drug d ON d.item_seq=c.item_seq WHERE"
                + " c.photo_id=? ORDER BY read_no,candidate_rank",
            p.id());
    var grouped = new LinkedHashMap<Integer, List<Map<String, Object>>>();
    for (var value : values)
      grouped
          .computeIfAbsent(((Number) value.get("read_no")).intValue(), k -> new ArrayList<>())
          .add(value);
    List<PhotoIdentificationResponse.DrugGroup> groups = new ArrayList<>();
    for (var entry : grouped.entrySet()) {
      var first = entry.getValue().getFirst();
      var candidates = new ArrayList<PhotoIdentificationResponse.Candidate>();
      for (var value : entry.getValue()) {
        String code = (String) value.get("item_seq");
        if (code != null)
          candidates.add(
              new PhotoIdentificationResponse.Candidate(
                  code,
                  (String) value.get("name"),
                  (String) value.get("maker"),
                  (String) value.get("etc_otc"),
                  (String) value.get("image_url"),
                  bool(value.get("cancelled")),
                  registered.contains(code),
                  bool(value.get("print_matched")),
                  (String) value.get("print_front"),
                  (String) value.get("print_back"),
                  (String) value.get("shape"),
                  (String) value.get("color"), (String) value.get("material")));
      }
      String lookup = (String) first.get("lookup_status");
      if (candidates.isEmpty() && "OK".equals(lookup))
        lookup = "FAILED"; // 05 이전 키워드 행을 실제 품목 후보로 오인하지 않는다.
      groups.add(
          new PhotoIdentificationResponse.DrugGroup(
              entry.getKey(), (String) first.get("read_name"), lookup, List.copyOf(candidates)));
    }
    var candidates =
        p.kind() == ImagePhotoKind.PILL
            ? groups.stream().flatMap(g -> g.candidates().stream()).toList()
            : List.<PhotoIdentificationResponse.Candidate>of();
    Boolean matched =
        candidates.stream().noneMatch(c -> c.printMatched() != null)
            ? null
            : candidates.stream().anyMatch(c -> Boolean.TRUE.equals(c.printMatched()));
    return new PhotoIdentificationResponse(
        p.kind(),
        p.id(),
        members(p.id()).stream().map(Photo::id).toList(),
        "OK",
        null,
        matched,
        List.copyOf(groups),
        candidates);
  }

  /** 같은 프로필 선택 저장을 직렬화한다. 다른 회원의 ID로 잠금을 얻거나 행을 만들지 않는다. */
  public void lockProfile(long profileId) {
    // 사진의 member FK와 프로필 삭제가 같은 회원 → 프로필 잠금 순서를 사용함.
    jdbc.queryForList("SELECT member_id FROM member WHERE member_id=? FOR UPDATE", Long.class, SecurityUtil.getCurrentMemberId());
    if (jdbc.queryForList(
            "SELECT profile_id FROM profile WHERE profile_id=? AND member_id=? FOR UPDATE",
            Long.class,
            profileId,
            SecurityUtil.getCurrentMemberId())
        .isEmpty()) throw new NotFoundException("프로필을 찾을 수 없습니다.");
  }

  public static void checkFresh(Photo p) {
    if (p.uploadedAt().isBefore(LocalDateTime.now(TimeZoneConfig.ZONE).minusMinutes(30)))
      throw invalid("시간이 지나 사진이 지워졌어요. 사진을 다시 올려 주세요.");
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void deleteRows(long rootId) {
    jdbc.queryForList("SELECT photo_id FROM photo WHERE photo_id=? FOR UPDATE", Long.class, rootId);
    jdbc.update(
        "DELETE FROM ocr_candidate WHERE photo_id IN(SELECT photo_id FROM photo WHERE photo_id=? OR"
            + " front_photo_id=?)",
        rootId,
        rootId);
    jdbc.update("DELETE FROM photo WHERE photo_id=? OR front_photo_id=?", rootId, rootId);
  }

  public List<Long> expiredRoots() {
    return jdbc.queryForList(
        "SELECT photo_id FROM photo WHERE front_photo_id IS NULL AND uploaded_at<? ORDER BY"
            + " photo_id LIMIT 100",
        Long.class,
        LocalDateTime.now(TimeZoneConfig.ZONE).minusMinutes(30));
  }

  public Set<String> referencedFiles() {
    return new HashSet<>(jdbc.queryForList("SELECT temp_path FROM photo", String.class));
  }

  public List<String> profileFileNames(long profileId) {
    return jdbc.queryForList(
        "SELECT temp_path FROM photo WHERE profile_id=?", String.class, profileId);
  }

  @Transactional
  public void deleteProfileRows(long profileId) {
    jdbc.update(
        "DELETE FROM ocr_candidate WHERE photo_id IN(SELECT photo_id FROM photo WHERE"
            + " profile_id=?)",
        profileId);
    jdbc.update("DELETE FROM photo WHERE profile_id=?", profileId);
  }

  private static Photo row(ResultSet rs, int index) throws SQLException {
    return new Photo(
        rs.getLong("photo_id"),
        rs.getLong("member_id"),
        rs.getObject("profile_id", Long.class),
        ImagePhotoKind.valueOf(rs.getString("kind")),
        rs.getObject("front_photo_id", Long.class),
        rs.getString("temp_path"),
        rs.getString("mime_type"),
        rs.getString("status"),
        rs.getObject("uploaded_at", LocalDateTime.class));
  }

  private static String mime(String name) {
    return name.endsWith(".png")
        ? "image/png"
        : name.endsWith(".webp") ? "image/webp" : "image/jpeg";
  }

  private static Boolean bool(Object value) {
    return value == null ? null : value instanceof Boolean b ? b : ((Number) value).intValue() != 0;
  }

  private static BusinessException invalid(String message) {
    return new BusinessException(ErrorCode.INVALID_INPUT, message);
  }
}
