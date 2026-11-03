package org.semiproject.pillbadeumserver.chat.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import org.semiproject.pillbadeumserver.common.config.TimeZoneConfig;
import org.semiproject.pillbadeumserver.drug.domain.StrengthMatch;

/**
 * V3-CHAT-002~005·007: 대화 속 제안 카드(openapi-v3 ChatProposal). 상태는 읽는 때의 값이다.
 * 대기인데 만료 시각이 지났으면 저장된 값과 관계없이 EXPIRED로 주고, decidedAt은 만료 시각이다.
 */
public record ChatProposalResponse(Long proposalId, Kind kind, Status status, Long profileId, Long messageId,
                                   OffsetDateTime createdAt, OffsetDateTime expiresAt, OffsetDateTime decidedAt,
                                   List<Item> items, Schedule schedule) {
    public enum Kind { REGISTER, SCHEDULE }
    public enum Status { PENDING, CONFIRMED, CANCELLED, EXPIRED }

    /** 카드 한 줄. 이름·제약사·사진·기본 이름·함량은 약 캐시(drug)의 지금 값이다. */
    public record Item(int itemNo, String itemSeq, String name, String maker, String imageUrl, String mention,
                       StrengthMatch strengthMatch, boolean alreadyRegistered, boolean chosen, Long myDrugId,
                       String baseName, String strengthText) {}

    /**
     * V3-CHAT-007 일정 제안의 '지금 → 바뀐 뒤'. 대상 약을 지웠으면 myDrugId·drugName이 null이고 확정할 수 없다.
     * @param drugName 약목록의 지금 이름(식별한 약은 품목명, 직접 입력한 약은 입력한 이름)
     */
    public record Schedule(Long myDrugId, String drugName, ScheduleValues current, ScheduleValues proposed) {}

    /**
     * 카드의 일정 값(openapi-v3 ScheduleValues). 칸 이름은 기존 복약 일정 응답과 같다. 일정이 없으면 날짜·시각이 null이고,
     * 시각은 저장 규칙처럼 이른 시각부터다. 제안 표(SQL 16)의 JSON 칸도 이 모양이다.
     */
    public record ScheduleValues(LocalDate startDate, LocalDate endDate, List<String> doseTimes, Integer dosePerTime,
                                 String timing) {}

    /** 저장된 값과 지금 시각으로 등록 제안 카드를 만든다. */
    public static ChatProposalResponse of(Long proposalId, Kind kind, Status saved, Long profileId, Long messageId,
                                          LocalDateTime createdAt, LocalDateTime expiresAt, LocalDateTime decidedAt,
                                          List<Item> items, LocalDateTime now) {
        return of(proposalId, kind, saved, profileId, messageId, createdAt, expiresAt, decidedAt, items, null, now);
    }

    /** 저장된 값과 지금 시각으로 카드를 만든다. 시각은 기존 메시지처럼 +09:00이다. 등록 제안은 schedule이 null이다. */
    public static ChatProposalResponse of(Long proposalId, Kind kind, Status saved, Long profileId, Long messageId,
                                          LocalDateTime createdAt, LocalDateTime expiresAt, LocalDateTime decidedAt,
                                          List<Item> items, Schedule schedule, LocalDateTime now) {
        boolean lapsed = saved == Status.PENDING && !now.isBefore(expiresAt);
        return new ChatProposalResponse(proposalId, kind, lapsed ? Status.EXPIRED : saved, profileId, messageId,
                offset(createdAt), offset(expiresAt), lapsed ? offset(expiresAt) : offset(decidedAt), List.copyOf(items),
                schedule);
    }

    private static OffsetDateTime offset(LocalDateTime value) {
        return value == null ? null : value.atZone(TimeZoneConfig.ZONE).toOffsetDateTime();
    }
}
