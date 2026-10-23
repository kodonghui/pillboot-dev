package org.semiproject.pillbadeumserver.drug.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/*  프로필이 등록한 내 약 (my_drug 테이블)
 *  ----------------------------------------------------------------------------------
 *  한 행 = 한 프로필이 복용하는 약 하나와 그 복용정보(1회 복용량, 복용 시각, 기간).
 *  - 식별한 약은 drug를 참조하고, 직접 입력한 약은 item_seq=NULL과 custom_name을 사용한다.
 *  - profile 엔티티가 아직 없으므로 profile_id 는 숫자 그대로 들고 있는다.
 *    (Profile 엔티티가 생기면 @ManyToOne 으로 바꿀지 프로필 담당과 정한다)
 *  ----------------------------------------------------------------------------------
 *  - 시각 컬럼 이름이 registered_at / updated_at 이라 BaseTimeEntity 를 상속하지 않고 직접 감사(auditing)한다.
 *  - dose_times 는 '07:00,11:00,17:00' 처럼 쉼표로 이은 문자열로 저장한다. 자바에서는 List<LocalTime> 으로 다룬다.
 *  - 저장 · 수정 규칙(복용량, 기간, 복용 시각)은 이 엔티티 안에서 검사해 어디서 바꾸든 같은 규칙을 지킨다.
 *  - 약 삭제는 행을 지우고, 일정 삭제는 clearSchedule()로 일정 필드 세 개만 비운다.
 *  - 시작일·종료일·복용 시각이 모두 NULL이면 일정 없는 약이다. 일부만 비우는 것은 금지한다.
 * */
@Entity
@Table(name = "my_drug")
@EntityListeners(AuditingEntityListener.class)
public class MyDrug {

    // 기간을 지정해 등록할 때 시각을 보내지 않으면 앱에서 이 기본값을 쓴다.
    public static final List<LocalTime> DEFAULT_DOSE_TIMES =
            List.of(LocalTime.of(7, 0), LocalTime.of(11, 0), LocalTime.of(17, 0));

    public static final int MAX_DOSE_TIMES = 3;   // 하루 최대 복용 횟수
    public static final int MAX_DOSE_PER_TIME = 10;   // 1회 최대 개수 (팀 명세. 요청 검증이 없는 예전 경로도 막는다)
    public static final int MAX_TIMING_LENGTH = 50;

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final String TIME_DELIMITER = ",";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "my_drug_id")
    private Long myDrugId;

    @Column(name = "profile_id", nullable = false, updatable = false)
    private Long profileId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_seq", updatable = false)
    private Drug drug;

    @Column(name = "custom_name", length = 100, updatable = false)
    private String customName;

    @Column(name = "dose_per_time", nullable = false)
    private int dosePerTime;            // 한 번에 복용하는 개수

    @Column(name = "dose_confirmed", nullable = false)
    private boolean doseConfirmed = true;

    @Column(name = "timing", length = 50)
    private String timing;              // 식후 30분, 식전 등 (선택)

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "dose_times", length = 50)
    private String doseTimes;           // '07:00,11:00,17:00'

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 30, nullable = false, updatable = false)
    private DrugSource source;

    @CreatedDate
    @Column(name = "registered_at", nullable = false, updatable = false)
    private LocalDateTime registeredAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // JPA 전용 기본 생성자
    protected MyDrug() {
    }

    // 확인한 약 저장 (POST /api/profiles/{profileId}/drugs)
    // 기간이 있으면 생략한 시각에 기본값을 쓴다. 기간·시각을 모두 생략하면 일정 없이 등록한다.
    public static MyDrug create(Long profileId, Drug drug, DrugSource source,
                                int dosePerTime, String timing,
                                LocalDate startDate, LocalDate endDate, List<LocalTime> doseTimes) {

        MyDrug myDrug = new MyDrug();
        myDrug.profileId = Objects.requireNonNull(profileId, "profileId");
        myDrug.drug = Objects.requireNonNull(drug, "drug");
        myDrug.source = Objects.requireNonNull(source, "source");

        boolean noSchedule = startDate == null && endDate == null && (doseTimes == null || doseTimes.isEmpty());
        List<LocalTime> times = noSchedule ? null
                : (doseTimes == null || doseTimes.isEmpty()) ? DEFAULT_DOSE_TIMES : doseTimes;
        myDrug.apply(dosePerTime, timing, startDate, endDate, times);
        return myDrug;
    }

    /** 약품을 특정하지 않은 일정도 기존 내 약 행에 저장한다. */
    public static MyDrug createCustom(Long profileId, String customName) {
        if (customName == null || customName.isBlank() || customName.strip().length() > 100) {
            throw invalid("직접 입력한 약 이름은 1~100자로 입력해주세요.");
        }
        MyDrug myDrug = new MyDrug();
        myDrug.profileId = Objects.requireNonNull(profileId, "profileId");
        myDrug.customName = customName.strip();
        myDrug.source = DrugSource.MANUAL;
        myDrug.apply(1, "식후 30분", null, null, null);
        myDrug.doseConfirmed = false;
        return myDrug;
    }

    // 복용정보 수정 (복약 일정 PUT /api/profiles/{profileId}/drugs/{myDrugId}/schedule 에서 쓴다)
    // null 로 넘긴 값은 바꾸지 않는다. 바뀐 값과 기존 값을 합친 결과로 다시 검사한다.
    // timing 을 지우려면 빈 문자열("")을 넘긴다.
    public void changeDoseInfo(Integer dosePerTime, String timing,
                               LocalDate startDate, LocalDate endDate, List<LocalTime> doseTimes) {

        apply(dosePerTime != null ? dosePerTime : this.dosePerTime,
                timing != null ? timing : this.timing,
                startDate != null ? startDate : this.startDate,
                endDate != null ? endDate : this.endDate,
                doseTimes != null ? doseTimes : this.doseTimes == null ? null : getDoseTimes());
        if (dosePerTime != null) doseConfirmed = true;
    }

    /** 선택한 내 약의 일정만 제거한다. 약·프로필·1회 개수·복용 때·등록 경로는 유지한다. */
    public void clearSchedule() {
        this.startDate = null;
        this.endDate = null;
        this.doseTimes = null;
    }

    public boolean hasSchedule() {
        return startDate != null && endDate != null && doseTimes != null;
    }

    // 이 약이 해당 프로필의 것인지 (다른 프로필 약을 경로에 넣은 요청을 막는다)
    public boolean belongsTo(Long profileId) {
        return Objects.equals(this.profileId, profileId);
    }

    // 검사를 모두 통과한 뒤에만 필드를 바꾼다. (중간에 실패하면 엔티티는 그대로)
    private void apply(int dosePerTime, String timing,
                       LocalDate startDate, LocalDate endDate, List<LocalTime> doseTimes) {

        if (dosePerTime < 1) {
            throw invalid("1회 복용량은 1개 이상이어야 합니다.");
        }
        if (dosePerTime > MAX_DOSE_PER_TIME) {
            throw invalid("1회 개수는 1부터 " + MAX_DOSE_PER_TIME + "까지 숫자로 넣어 주세요.");
        }
        boolean noSchedule = startDate == null && endDate == null && doseTimes == null;
        if (!noSchedule && (startDate == null || endDate == null || doseTimes == null)) {
            throw invalid("복용 시작일과 종료일을 입력해주세요.");
        }
        if (!noSchedule && startDate.isAfter(endDate)) {
            throw invalid("복용 시작일은 종료일보다 늦을 수 없습니다.");
        }

        String normalizedTiming = (timing == null || timing.isBlank()) ? null : timing.strip();
        if (normalizedTiming != null && normalizedTiming.length() > MAX_TIMING_LENGTH) {
            throw invalid("복용 시점은 " + MAX_TIMING_LENGTH + "자 이하로 입력해주세요.");
        }

        String joinedTimes = noSchedule ? null : joinDoseTimes(doseTimes);

        this.dosePerTime = dosePerTime;
        this.timing = normalizedTiming;
        this.startDate = startDate;
        this.endDate = endDate;
        this.doseTimes = joinedTimes;
    }

    // 복용 시각 1~3개, 중복 없음. 이른 시각부터 정렬해서 'HH:mm,HH:mm' 으로 만든다.
    // 초 단위는 버리고 분까지만 저장한다.
    private static String joinDoseTimes(List<LocalTime> doseTimes) {

        if (doseTimes == null || doseTimes.isEmpty() || doseTimes.size() > MAX_DOSE_TIMES) {
            throw invalid("복용 시각은 하루 1~" + MAX_DOSE_TIMES + "개를 입력해주세요.");
        }
        // List.of(...) 는 contains(null) 에서 NullPointerException 을 던지므로 stream 으로 검사한다.
        if (doseTimes.stream().anyMatch(Objects::isNull)) {
            throw invalid("복용 시각을 확인해주세요.");
        }

        List<LocalTime> minutes = doseTimes.stream()
                .map(time -> time.withSecond(0).withNano(0))
                .sorted()
                .toList();

        if (new HashSet<>(minutes).size() != minutes.size()) {
            throw invalid("같은 복용 시각을 두 번 입력할 수 없습니다.");
        }

        return minutes.stream()
                .map(TIME_FORMAT::format)
                .collect(Collectors.joining(TIME_DELIMITER));
    }

    private static BusinessException invalid(String detail) {
        return new BusinessException(ErrorCode.INVALID_INPUT, detail);
    }

    public Long getMyDrugId() {
        return myDrugId;
    }

    public Long getProfileId() {
        return profileId;
    }

    public Drug getDrug() {
        return drug;
    }

    public String getCustomName() {
        return customName;
    }

    public int getDosePerTime() {
        return dosePerTime;
    }

    public Integer getConfirmedDosePerTime() { return doseConfirmed ? dosePerTime : null; }
    public boolean isDoseConfirmed() { return doseConfirmed; }

    public String getTiming() {
        return timing;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    // 저장된 '07:00,11:00' 문자열을 시각 목록으로 바꿔 돌려준다.
    public List<LocalTime> getDoseTimes() {

        if (doseTimes == null || doseTimes.isBlank()) {
            return List.of();
        }
        try {
            return Arrays.stream(doseTimes.split(TIME_DELIMITER))
                    .map(String::strip)
                    .map(value -> LocalTime.parse(value, TIME_FORMAT))
                    .toList();
        } catch (DateTimeParseException e) {
            throw new IllegalStateException("my_drug.dose_times 형식이 올바르지 않습니다. myDrugId=" + myDrugId, e);
        }
    }

    public DrugSource getSource() {
        return source;
    }

    public LocalDateTime getRegisteredAt() {
        return registeredAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
