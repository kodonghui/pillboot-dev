package org.semiproject.pillbadeumserver.chat.service;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;
import org.semiproject.pillbadeumserver.chat.dto.ChatProposalResponse.ScheduleValues;
import org.semiproject.pillbadeumserver.drug.domain.MyDrug;
import org.semiproject.pillbadeumserver.medication.dto.MedicationDtos.ScheduleRequest;
import org.semiproject.pillbadeumserver.medication.dto.MedicationVoiceDtos.ParsedCommand;

/**
 * V3-CHAT-007: 단문 일정 해석기(MedicationVoiceInterpreter)가 읽은 값으로 카드의 '바뀐 뒤' 일정을 만든다. 저장하지 않는다.
 * 일정을 바꾸는 요청이라 말하지 않은 값은 지금 일정을 그대로 둔다. 일정이 없는 약은 기존 단문 일정 명령의 기본값
 * (오늘 하루, 07:00·11:00·17:00)을 쓴다. 검사 규칙은 단문 일정 명령·일정 저장과 같고 안내만 대화체다.
 */
final class ScheduleDraft {
    // 일정 저장 요청(ScheduleRequest)의 복용 때 한도. 확정 때 같은 요청으로 저장하므로 여기서 먼저 막는다.
    static final int MAX_TIMING = 20;
    private static final Pattern TIME = Pattern.compile("([01][0-9]|2[0-3]):[0-5][0-9]");
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private ScheduleDraft() {}

    /**
     * my_drug에 저장된 값을 카드 값으로 바꾼다. 일정이 없으면 날짜·시각이 null이고, 시각은 이른 시각부터다.
     *
     * @param confirmedDose 확인된 1회 개수. 확인되지 않았으면 null(내 약 응답과 같은 규칙)
     */
    static ScheduleValues stored(LocalDate start, LocalDate end, String doseTimes, Integer confirmedDose, String timing) {
        boolean scheduled = start != null && end != null && doseTimes != null;
        var times = scheduled ? Arrays.stream(doseTimes.split(",")).map(String::strip).sorted().toList() : null;
        return new ScheduleValues(scheduled ? start : null, scheduled ? end : null, times, confirmedDose, blankToNull(timing));
    }

    /**
     * @param current 지금 일정(stored)
     * @param storedDose 저장된 1회 개수. 지금 개수가 확인되지 않았고 개수를 말하지 않았을 때 쓴다(확정하면 확인됨으로 저장됨)
     * @param today 한국 날짜. 기간만 말했거나 일정 없는 약의 시작일이다
     * @throws Rejected 값이 규칙 밖임. 메시지는 회원에게 그대로 보이는 안내다
     */
    static ScheduleValues proposed(ParsedCommand parsed, ScheduleValues current, int storedDose, LocalDate today) {
        boolean scheduled = current.startDate() != null;
        try {
            if (parsed.durationDays() != null && parsed.durationDays() < 1) throw new Rejected("복용 기간은 하루 이상으로 말해 주세요.");
            // 기간만 말하면 오늘부터 센다. 시작일도 기간도 말하지 않으면 지금 시작일을 둔다.
            LocalDate start = parsed.startDate() != null ? LocalDate.parse(parsed.startDate())
                    : parsed.durationDays() != null || !scheduled ? today : current.startDate();
            LocalDate end;
            if (parsed.endDate() != null) {
                end = LocalDate.parse(parsed.endDate());
                if (parsed.durationDays() != null && !end.equals(start.plusDays(parsed.durationDays() - 1L)))
                    throw new Rejected("종료일과 복용 기간이 서로 맞지 않아요. 둘 중 하나만 다시 말해 주세요.");
            } else if (parsed.durationDays() != null) {
                end = start.plusDays(parsed.durationDays() - 1L);
            } else {
                end = scheduled ? current.endDate() : start;
            }
            if (start.isAfter(end)) throw new Rejected("시작일이 종료일보다 늦어요. 종료일이나 복용 기간을 함께 말해 주세요.");
            int dose = parsed.dosePerTime() != null ? parsed.dosePerTime()
                    : current.dosePerTime() != null ? current.dosePerTime() : storedDose;
            if (dose < 1 || dose > MyDrug.MAX_DOSE_PER_TIME) throw new Rejected("1회 개수는 1~10개로 말해 주세요.");
            String timing = parsed.timing() != null ? blankToNull(parsed.timing()) : current.timing();
            if (timing != null && timing.length() > MAX_TIMING) throw new Rejected("복용 때(식후 30분 등)는 20자 이내로 말해 주세요.");
            return new ScheduleValues(start, end, times(parsed, current, scheduled), dose, timing);
        } catch (DateTimeException exception) {
            throw new Rejected("날짜와 기간을 다시 말해 주세요.");
        }
    }

    /** 말한 시각을 쓰고, 하루 횟수만 말하면 기본 시각을 앞에서부터 쓴다(지금 횟수와 같으면 지금 시각). 둘 다 없으면 지금 시각이다. */
    private static List<String> times(ParsedCommand parsed, ScheduleValues current, boolean scheduled) {
        Integer count = parsed.dailyCount();
        if (count != null && (count < 1 || count > MyDrug.MAX_DOSE_TIMES)) throw new Rejected("복용 시각은 하루 1~3번으로 말해 주세요.");
        var said = parsed.doseTimes();
        if (said != null) {
            if (said.isEmpty() || said.size() > MyDrug.MAX_DOSE_TIMES) throw new Rejected("복용 시각은 하루 1~3번으로 말해 주세요.");
            if (count != null && count != said.size()) throw new Rejected("하루 횟수와 복용 시각 수가 달라요. 다시 말해 주세요.");
            if (said.stream().anyMatch(time -> time == null || !TIME.matcher(time).matches()))
                throw new Rejected("복용 시각을 '아침 8시'나 '20시 30분'처럼 다시 말해 주세요.");
            if (new HashSet<>(said).size() != said.size()) throw new Rejected("같은 복용 시각을 두 번 말씀하셨어요. 다시 말해 주세요.");
            return said.stream().sorted().toList();
        }
        if (count != null) {
            if (scheduled && current.doseTimes().size() == count) return current.doseTimes();
            return format(MyDrug.DEFAULT_DOSE_TIMES.subList(0, count));
        }
        return scheduled ? current.doseTimes() : format(MyDrug.DEFAULT_DOSE_TIMES);
    }

    /** 확정 때 기존 일정 저장(MedicationScheduleService.save)에 넘길 전체 교체 요청. */
    static ScheduleRequest request(ScheduleValues values) {
        return new ScheduleRequest(values.startDate(), values.endDate(),
                values.doseTimes().stream().map(LocalTime::parse).toList(), values.dosePerTime(), values.timing());
    }

    private static List<String> format(List<LocalTime> times) {
        return times.stream().map(HH_MM::format).toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** 해석한 값이 규칙 밖이라 카드를 만들 수 없음. 저장 없이 이 안내로 답한다. */
    static final class Rejected extends RuntimeException {
        Rejected(String message) { super(message, null, false, false); }
    }
}
