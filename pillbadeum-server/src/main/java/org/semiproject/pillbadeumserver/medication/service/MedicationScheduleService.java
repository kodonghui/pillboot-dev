package org.semiproject.pillbadeumserver.medication.service;

import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.common.exception.NotFoundException;
import org.semiproject.pillbadeumserver.drug.domain.MyDrug;
import org.semiproject.pillbadeumserver.medication.domain.MedicationRepository;
import org.semiproject.pillbadeumserver.medication.domain.MedicationScheduleWriteRepository;
import org.semiproject.pillbadeumserver.medication.domain.MedicationTarget;
import org.semiproject.pillbadeumserver.medication.dto.MedicationDtos.ScheduleRequest;
import org.semiproject.pillbadeumserver.medication.dto.MedicationDtos.ScheduleResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 일정 관리의 진입점. 컨트롤러는 HTTP만, 이 클래스는 소유권·트랜잭션·업무 순서를 담당한다. */
@Service
@Transactional(readOnly = true)
public class MedicationScheduleService {
    public record ScheduleChange(long myDrugId, ScheduleRequest request) {}
    private final MedicationRepository reads;
    private final MedicationScheduleWriteRepository writes;
    private final Clock clock;

    @Autowired
    public MedicationScheduleService(MedicationRepository reads, MedicationScheduleWriteRepository writes) {
        this(reads, writes, Clock.system(ZoneId.of("Asia/Seoul")));
    }

    // 테스트는 고정 시계를 넣어 자정·날짜 경계를 재현한다. 공통 Clock 빈은 추가하지 않는다.
    MedicationScheduleService(MedicationRepository reads, MedicationScheduleWriteRepository writes, Clock clock) {
        this.reads = reads;
        this.writes = writes;
        this.clock = clock;
    }

    public ScheduleResponse get(long memberId, long profileId, long myDrugId) {
        MedicationTarget target = ownedTarget(memberId, profileId, myDrugId);
        if (!target.hasSchedule()) throw noSchedule();
        return ScheduleResponse.from(target, reads.pushEnabled(memberId));
    }

    @Transactional
    public ScheduleResponse save(long memberId, long profileId, long myDrugId, ScheduleRequest request) {
        MedicationTarget target = ownedTarget(memberId, profileId, myDrugId);
        MyDrug myDrug = lock(profileId, myDrugId);
        request.applyTo(myDrug);
        // 조회한 MyDrug는 영속 상태다. 트랜잭션 커밋 시 변경 감지로 UPDATE한다.
        // 새 my_drug 행이나 복용 날짜별 행은 만들지 않는다.
        return ScheduleResponse.from(myDrug, target, reads.pushEnabled(memberId));
    }

    /** 두 약의 전체 입력·소유권·존재를 확인하고 모든 행을 잠근 뒤 한 트랜잭션에서 변경한다. */
    @Transactional
    public List<ScheduleResponse> saveBatch(long memberId, long profileId, List<ScheduleChange> changes) {
        if (changes == null || changes.size() != 2 || changes.stream().anyMatch(change -> change == null
                || change.myDrugId() < 1 || change.request() == null)
                || changes.stream().map(ScheduleChange::myDrugId).distinct().count() != 2) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "함께 등록할 두 약을 다시 선택해주세요.");
        }
        var ordered = changes.stream().sorted(Comparator.comparingLong(ScheduleChange::myDrugId)).toList();
        var targets = new ArrayList<MedicationTarget>();
        for (var change : ordered) {
            targets.add(ownedTarget(memberId, profileId, change.myDrugId()));
            // 검증용 비영속 객체에 전체 요청을 검사한다. 실제 행은 두 잠금이 끝나기 전에는 바꾸지 않는다.
            change.request().applyTo(MyDrug.createCustom(profileId, "일정 입력 검증"));
        }
        var locked = ordered.stream().map(change -> lock(profileId, change.myDrugId())).toList();
        for (int i = 0; i < ordered.size(); i++) ordered.get(i).request().applyTo(locked.get(i));
        boolean pushEnabled = reads.pushEnabled(memberId);
        var result = new ArrayList<ScheduleResponse>();
        for (int i = 0; i < ordered.size(); i++) result.add(ScheduleResponse.from(locked.get(i), targets.get(i), pushEnabled));
        return List.copyOf(result);
    }

    /** Live 저장 확정 시에만 직접 입력한 내 약과 일정을 하나의 트랜잭션으로 만든다. */
    @Transactional
    public ScheduleResponse createCustom(long memberId, long profileId, String name, ScheduleRequest request) {
        var profile = reads.profiles(memberId).stream().filter(p -> p.profileId() == profileId).findFirst()
                .orElseThrow(() -> new NotFoundException("조회할 수 있는 프로필이 없습니다."));
        MyDrug myDrug = MyDrug.createCustom(profileId, name);
        request.applyTo(myDrug);
        myDrug = writes.save(myDrug);
        var target = new MedicationTarget(myDrug.getMyDrugId(), profileId, profile.profileName(), profile.relationship(),
                myDrug.getCustomName(), myDrug.getDosePerTime(), myDrug.getTiming(), null, null, null, null);
        return ScheduleResponse.from(myDrug, target, reads.pushEnabled(memberId));
    }

    @Transactional
    public ScheduleResponse stop(long memberId, long profileId, long myDrugId) {
        MedicationTarget target = ownedTarget(memberId, profileId, myDrugId);
        MyDrug myDrug = lock(profileId, myDrugId);
        if (!myDrug.hasSchedule()) throw noSchedule();
        LocalDate today = LocalDate.now(clock);
        if (myDrug.getStartDate().isAfter(today)) {
            throw conflict("아직 시작하지 않은 일정입니다. 일정 삭제를 이용해주세요.");
        }
        if (myDrug.getEndDate().isBefore(today)) {
            throw conflict("이미 종료된 일정입니다.");
        }
        if (!myDrug.getEndDate().equals(today)) {
            myDrug.changeDoseInfo(null, null, null, today, myDrug.getDoseTimes());
        }
        // 종료일은 오늘을 포함한다. 오늘 남은 복용 시각은 유지하고 내일부터 제외한다.
        return ScheduleResponse.from(myDrug, target, reads.pushEnabled(memberId));
    }

    @Transactional
    public void delete(long memberId, long profileId, long myDrugId) {
        ownedTarget(memberId, profileId, myDrugId);
        MyDrug myDrug = lock(profileId, myDrugId);
        if (!myDrug.hasSchedule()) throw noSchedule();
        myDrug.clearSchedule();
        // 약 행과 다른 약의 일정은 유지한다. 커밋 시 선택한 행의 일정 필드만 NULL이 된다.
    }

    private MedicationTarget ownedTarget(long memberId, long profileId, long myDrugId) {
        // SQL에서 회원·프로필·내 약을 함께 거른다. 타 회원 데이터도 동일하게 404로 숨긴다.
        return reads.find(memberId, profileId, myDrugId)
                .orElseThrow(() -> new NotFoundException("조회할 수 있는 내 약 일정이 없습니다."));
    }

    private MyDrug lock(long profileId, long myDrugId) {
        return writes.findForUpdate(profileId, myDrugId)
                .orElseThrow(() -> new NotFoundException("이미 삭제된 약입니다."));
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.DUPLICATE, message);
    }

    private static NotFoundException noSchedule() {
        return new NotFoundException("등록된 복약 일정이 없습니다.");
    }
}
