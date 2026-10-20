package org.semiproject.pillbadeumserver.chat.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.semiproject.pillbadeumserver.chat.domain.AnswerSource;
import org.semiproject.pillbadeumserver.chat.dto.DurNotice;
import org.semiproject.pillbadeumserver.common.config.TimeZoneConfig;
import org.springframework.stereotype.Component;

/** CHAT-008: 조회 범위와 대조 결과를 분리한다. 모델이 아닌 서버가 등록 품목코드·확정 사본으로 판단한다. */
@Component
public class DurEvaluator {
    // 공식 응답에는 별도 나이 칸이 없다. 단순한 단일 조건만 읽고 복합 조건은 확인 필요로 남긴다.
    private static final Pattern AGE = Pattern.compile("^(?:만\\s*)?(\\d{1,3})\\s*(세|개월)\\s*(미만|이하|이상|초과)"
            + "(?:\\s*(?:의\\s*)?(?:소아|영아|어린이|청소년|환자))?[.!]?$");

    public Evaluation evaluate(AnswerInput input, List<DurLookup> lookups) {
        if (input.medications().isEmpty()) {
            if (input.unidentifiedDrugNames().isEmpty()) return new Evaluation(new DurNotice("NO_DRUG", null, 0, List.of(), List.of(),
                    "약을 등록하면 내 약의 금기 정보를 확인해 드려요."), List.of());
            return new Evaluation(new DurNotice("FAILED", null, 0, input.unidentifiedDrugNames(), List.of(),
                    "직접 입력한 약의 품목코드를 확인하지 못해 금기를 대조할 수 없어요. 약을 식별해 등록하거나 약사에게 확인해 주세요."), List.of());
        }
        LocalDate today = LocalDate.now(TimeZoneConfig.ZONE);
        Map<String, AnswerInput.Medication> drugs = new LinkedHashMap<>();
        input.medications().forEach(m -> drugs.putIfAbsent(m.itemSeq(), m));
        var types = DurLookupService.requiredTypes(input, drugs.size(), today);
        Map<String, DurLookup> byKey = new LinkedHashMap<>();
        lookups.forEach(lookup -> byKey.put(key(lookup.itemSeq(), lookup.type()), lookup));
        var provenance = new ArrayList<AnswerSource>();
        var unchecked = new ArrayList<>(input.unidentifiedDrugNames());
        var warnings = new LinkedHashMap<String, DurNotice.Item>();
        int successful = 0;
        int checked = 0;
        boolean unresolved = !input.unidentifiedDrugNames().isEmpty();
        boolean found = false;
        for (var drug : drugs.values()) {
            boolean complete = true;
            LocalDate date = today;
            for (var type : types) {
                var lookup = byKey.get(key(drug.itemSeq(), type));
                if (lookup == null || !lookup.success()) { complete = false; continue; }
                successful++;
                if (lookup.updatedAt().isBefore(date)) date = lookup.updatedAt();
                for (var row : lookup.rows()) {
                    String content = row.content();
                    boolean matched = true;
                    List<String> names = List.of(drug.name());
                    String pair = drug.itemSeq();
                    if (type == DurLookup.Type.INTERACTION) {
                        var partner = drugs.get(row.partnerItemSeq());
                        if (partner == null || partner.itemSeq().equals(drug.itemSeq())) continue;
                        names = List.of(drug.name(), partner.name());
                        pair = drug.itemSeq().compareTo(partner.itemSeq()) < 0
                                ? drug.itemSeq() + ":" + partner.itemSeq() : partner.itemSeq() + ":" + drug.itemSeq();
                    } else if (type == DurLookup.Type.AGE) {
                        var match = ageMatch(input.birthYear(), today, content);
                        if (match == Match.NO) continue;
                        if (match == Match.UNRESOLVED) {
                            unresolved = true;
                            matched = false;
                            content = "연령 조건을 자동으로 대조하지 못했어요. 약사에게 확인해 주세요. 공식 내용: " + content;
                        } else if (match == Match.POSSIBLE) content = "출생연도만으로 나이를 추정해 해당할 수 있어요. 공식 내용: " + content;
                    } else if (type == DurLookup.Type.ELDERLY) {
                        long years = today.getYear() - input.birthYear();
                        if (years >= 0 && years < 65) continue;
                        if (years == 65) content = "만 나이가 65세에 해당할 수 있어요. 공식 내용: " + content;
                        else if (years < 0 || years > 130) {
                            unresolved = true;
                            matched = false;
                            content = "출생연도 정보로 노인주의 여부를 대조하지 못했어요. 공식 내용: " + content;
                        }
                    } else if (type == DurLookup.Type.PREGNANCY) {
                        if ("MALE".equals(input.gender()) || Boolean.FALSE.equals(input.pregnant())) continue;
                        if (!"FEMALE".equals(input.gender()) || input.pregnant() == null) {
                            content = "임신 중이라면 주의가 필요해요. 의사나 약사와 상담해 주세요. 공식 내용: " + content;
                        }
                    }
                    found |= matched;
                    warnings.putIfAbsent(type + ":" + pair + ":" + content,
                            new DurNotice.Item(type.name(), names, content, row.updatedAt()));
                }
            }
            if (complete) checked++; else unchecked.add(drug.name());
            provenance.add(new AnswerSource(drug.itemSeq(), drug.name(), AnswerSource.Type.DUR, date, complete, false));
        }
        int total = drugs.size() * types.size();
        String lookup = successful == 0 ? "FAILED" : successful == total && input.unidentifiedDrugNames().isEmpty() ? "ALL" : "PARTIAL";
        // 자동 대조 불가 안내 자체를 실제 금기 해당으로 세지 않는다.
        String result = found ? "FOUND" : successful == 0 || unresolved ? null : "NONE";
        String scope = String.join("·", types.stream().map(DurLookup.Type::label).toList());
        String message = successful == 0
                ? "지금은 금기 정보를 확인하지 못했어요. 금기가 없다는 뜻은 아니니 약사에게 확인해 주세요."
                : "이번 요청에서 " + drugs.size() + "개 등록 약의 " + scope + " 중 " + successful + "/" + total + "개 조회를 확인했어요. "
                    + (found ? "확인된 금기·주의 또는 해당 가능 항목이 있어요. " : unresolved
                        ? "품목·연령 조건을 추가로 확인해야 해요. " : "확인한 유형·프로필 조건에서는 해당 항목을 찾지 못했어요. ")
                    + (unchecked.isEmpty() ? "" : "일부 약·유형은 확인하지 못했어요. ")
                    + "모든 약의 안전성을 보장하는 결과는 아니에요. 복용 여부는 의사나 약사에게 확인해 주세요.";
        return new Evaluation(new DurNotice(lookup, result, checked, List.copyOf(unchecked),
                List.copyOf(warnings.values()), message), List.copyOf(provenance));
    }

    /** CHAT-008: 판정·본문 경고와 카드 표시를 분리한다. 발견 경고는 숨기지 않고 일반 안내의 반복 카드만 줄인다. */
    public DurNotice cardNotice(AnswerInput input, DurNotice notice) {
        if ("FOUND".equals(notice.result())) return notice;
        if (input.firstAnswer()) return null;
        String question = input.question().replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
        boolean medicationQuestion = List.of("약", "복용", "복약", "먹", "병용", "금기", "부작용", "성분", "용량", "임신", "임부")
                .stream().anyMatch(question::contains)
                || input.medications().stream().map(m -> m.name().replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT))
                .filter(name -> !name.isBlank()).anyMatch(question::contains);
        return medicationQuestion ? notice : null;
    }

    /** 출생연도의 가능한 만 나이 범위 전체가 조건 밖일 때만 NO를 반환한다. 복합 문구를 추측하지 않는다. */
    private Match ageMatch(long birthYear, LocalDate today, String content) {
        long years = today.getYear() - birthYear;
        if (years < 0 || years > 130) return Match.UNRESOLVED;
        var matcher = AGE.matcher(content);
        if (!matcher.matches()) return Match.UNRESOLVED;
        int threshold = Integer.parseInt(matcher.group(1));
        if (threshold > ("세".equals(matcher.group(2)) ? 130 : 1560)) return Match.UNRESOLVED;
        long min = Math.max(0, years - 1);
        long max = years;
        if ("개월".equals(matcher.group(2))) {
            min = years == 0 ? 0 : (years - 1) * 12;
            max = years * 12 + 11;
        }
        boolean low = compare(min, threshold, matcher.group(3));
        boolean high = compare(max, threshold, matcher.group(3));
        return low && high ? Match.YES : low || high ? Match.POSSIBLE : Match.NO;
    }

    private boolean compare(long age, int threshold, String comparison) {
        return switch (comparison) {
            case "미만" -> age < threshold;
            case "이하" -> age <= threshold;
            case "이상" -> age >= threshold;
            default -> age > threshold;
        };
    }

    private static String key(String code, DurLookup.Type type) { return code + ":" + type; }
    private enum Match { YES, POSSIBLE, NO, UNRESOLVED }
    public record Evaluation(DurNotice notice, List<AnswerSource> sources) {}
}
