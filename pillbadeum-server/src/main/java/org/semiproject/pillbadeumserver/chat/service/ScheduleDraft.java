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
 * (오늘 하루, 07:00·12:00·17:00)을 쓴다. 검사 규칙은 단문 일정 명령·일정 저장과 같고 안내만 대화체다.
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

    // 2026-11-08 QA('저녁 약은 7시 말고 9시로 바꿔줘'가 07:00·12:00·17:00을 21:00 하나로 바꿈): 때 이름과 횟수를 줄이는 말
    private static final Pattern SLOT = Pattern.compile("아침|점심|저녁");
    // 그 끼에만 먹거나 하루 횟수를 직접 말한 경우('저녁에만'·'하루 두 번 아침 8시 저녁 9시'·'점심 빼고')는 말한 시각만 남긴다
    private static final Pattern FEWER = Pattern.compile(
            "에만|만\\s*먹|(?:한|1)\\s*번(?:씩)?만|하루\\s*(?:[1-4]|한|두|세|네)\\s*(?:번|회)|(?:[1-4]|한|두|세|네)\\s*번\\s*(?:먹|복용)|빼");
    // 2026-11-08 QA 다시 돌림('둘 다 아침 8시 저녁 8시로 바꿔줘'가 점심을 남겨 08:00·13:00·20:00이 됨. 답변은 '8시·8시로 바뀐
    // 일정이 없다'고 함): 때를 둘 이상 늘어놓은 말은 그 시각들로 바꾼다(줄면 카드에 '하루 3번에서 2번으로' 줄). 때마다
    // '은·는'을 붙이거나('아침은 8시, 저녁은 9시로') '말고·대신'으로 고치면 그 때만 고친다
    private static final Pattern TOPIC = Pattern.compile("(아침|점심|저녁)\\s*(?:약|거|것|에)?\\s*(?:은|는|엔)");
    private static final Pattern INSTEAD = Pattern.compile("말고|대신");

    /**
     * 아침·점심·저녁 중 일부 때만 말하고 그 수만큼 시각을 말했으면(지금보다 적게, 하루 횟수는 말하지 않음) 지금 시각에서 그 때의
     * 시각만 바꾼다. 때가 둘 이상이면 때마다 '은·는'을 붙였거나 '말고·대신'으로 고친 말만 그렇게 본다. 때와 말한 시각은
     * 이른 것부터 짝짓고, 지금 시각의 때는 아침 11시 전·점심 11~15시·저녁 15시부터(그중 이른 시각)로 가른다. 그 때에 맞는 지금 시각이 없거나 '저녁에만'·'하루 한 번'·'빼'처럼 횟수를 줄이는 말이면 그대로 둔다.
     */
    static ParsedCommand slotEdit(String text, ParsedCommand parsed, ScheduleValues current) {
        var said = parsed.doseTimes();
        var now = current.doseTimes();
        if (text == null || said == null || said.isEmpty() || now == null || said.size() >= now.size()) return parsed;
        if (parsed.dailyCount() != null && parsed.dailyCount() != said.size() || FEWER.matcher(text).find()) return parsed;
        var slots = SLOT.matcher(text).results().map(m -> m.group()).distinct()
                .sorted(java.util.Comparator.comparingInt(ScheduleDraft::slotOrder)).toList();
        if (slots.size() != said.size() || said.stream().anyMatch(t -> t == null || !TIME.matcher(t).matches())) return parsed;
        if (slots.size() > 1 && !INSTEAD.matcher(text).find()
                && !TOPIC.matcher(text).results().map(m -> m.group(1)).collect(java.util.stream.Collectors.toSet()).containsAll(slots))
            return parsed;
        var sorted = said.stream().sorted().toList();
        var edited = new java.util.ArrayList<>(now);
        var used = new HashSet<Integer>();
        for (int i = 0; i < slots.size(); i++) {
            int index = slotIndex(slots.get(i), now);
            if (index < 0 || !used.add(index)) return parsed;
            edited.set(index, sorted.get(i));
        }
        return new ParsedCommand(parsed.intent(), parsed.myDrugId(), parsed.startDate(), parsed.endDate(),
                parsed.durationDays(), List.copyOf(edited), null, parsed.dosePerTime(), parsed.timing());
    }

    // 2026-11-08 QA 시나리오 01 다시 돌림('저녁 약은 7시 말고 9시로 바꿔줘'에 어느 약이냐고 되물음): '저녁 약'·'아침에 먹는 약'은
    // 그 때에 먹는 약들. '아침 약속'·'저녁 약국'은 아님
    private static final Pattern SLOT_GROUP = Pattern.compile("(아침|점심|저녁)\\s*(?:에\\s*)?(?:먹는\\s*)?약(?![속국])");

    /** 글이 '저녁 약'처럼 한 때에 먹는 약들을 가리키면 그 때('저녁'). 없거나 두 때 이상이면 null. */
    static String slotGroup(String text) {
        if (text == null) return null;
        var slots = SLOT_GROUP.matcher(text).results().map(m -> m.group(1)).distinct().toList();
        return slots.size() == 1 ? slots.getFirst() : null;
    }

    /** 저장된 복용 시각 가운데 그 때(아침 11시 전·점심 11~15시·저녁 15시부터)에 드는 시각이 있음. 일정 없는 약은 false. */
    static boolean takenAt(String slot, String storedDoseTimes) {
        if (storedDoseTimes == null || storedDoseTimes.isBlank()) return false;
        var times = Arrays.stream(storedDoseTimes.split(",")).map(String::strip).filter(t -> TIME.matcher(t).matches())
                .sorted().toList();
        return slotIndex(slot, times) >= 0;
    }

    private static int slotOrder(String slot) {
        return switch (slot) { case "아침" -> 0; case "점심" -> 1; default -> 2; };
    }

    /** 지금 시각(이른 것부터) 중 그 때의 시각 번호. 없으면 -1. */
    private static int slotIndex(String slot, List<String> times) {
        for (int i = 0; i < times.size(); i++) {
            int hour = Integer.parseInt(times.get(i).substring(0, 2));
            boolean fits = switch (slot) {
                case "아침" -> hour < 11;
                case "점심" -> hour >= 11 && hour < 15;
                default -> hour >= 15;
            };
            if (fits) return i;
        }
        return -1;
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
