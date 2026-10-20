package org.semiproject.pillbadeumserver.medication.service;

import org.semiproject.pillbadeumserver.medication.domain.MedicationTarget;
import org.semiproject.pillbadeumserver.medication.dto.MedicationLiveDtos.*;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** 삭제·중단은 모델의 ID 선택을 그대로 실행하지 않고, 소유한 일정과 사용자 원문으로 좁힌다. */
final class MedicationLiveTargetResolver {
    record Resolution(MedicationTarget target, String question) {}
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");

    Resolution resolve(Plan plan, StartRequest selection, List<MedicationTarget> owned,
                       List<Map<String, Object>> transcript) {
        String users = text(transcript);
        if (!authorized(plan, users)) return new Resolution(null, "삭제하거나 그만 먹을 일정을 말씀해주세요.");
        String profile = canonical(plan.profileHint());
        List<String> hints = plan.drugHints() == null ? List.of() : plan.drugHints();
        if (profile.length() > 100 || !users.contains(profile) || hints.size() > 10
                || hints.stream().anyMatch(hint -> hint == null || hint.isBlank() || hint.length() > 100
                        || !users.contains(canonical(hint)))) {
            return new Resolution(null, "어느 프로필의 어떤 약 일정인지 알려주세요.");
        }
        List<MedicationTarget> matches = owned.stream().filter(MedicationTarget::hasSchedule).toList();
        if (!profile.isEmpty()) {
            int best = matches.stream().mapToInt(target -> profileScore(profile, target)).max().orElse(0);
            matches = best == 0 ? List.of() : matches.stream().filter(target -> profileScore(profile, target) == best).toList();
        }
        if (!hints.isEmpty()) {
            int best = matches.stream().mapToInt(target -> drugScore(hints, target.drugName())).max().orElse(0);
            matches = best == 0 ? List.of() : matches.stream().filter(target -> drugScore(hints, target.drugName()) == best).toList();
        } else if (profile.isEmpty() && users.matches(".*(선택한|이약|이일정|현재선택|지금보고있는).*")) {
            matches = matches.stream().filter(target -> target.profileId() == selection.selectedProfileId()
                    && selection.selectedMyDrugId() != null && target.myDrugId() == selection.selectedMyDrugId()).toList();
        }
        if (matches.size() == 1) return new Resolution(matches.getFirst(), null);
        if (matches.isEmpty()) return new Resolution(null, "말씀하신 조건에 맞는 복약 일정이 없어요. 프로필이나 약 이름을 다시 알려주세요.");
        var profiles = matches.stream().map(MedicationTarget::profileName).distinct().toList();
        String choices = (profiles.size() > 1 ? profiles.stream() : matches.stream().map(MedicationTarget::drugName).distinct())
                .limit(3).collect(java.util.stream.Collectors.joining(", "));
        return new Resolution(null, profiles.size() > 1 ? choices + " 중 누구의 일정인가요?"
                : choices + " 중 어떤 약인가요? 함량이나 약 이름을 조금 더 알려주세요.");
    }

    private boolean authorized(Plan plan, String users) {
        String evidence = canonical(plan.actionEvidence());
        if (evidence.isEmpty() || !users.contains(evidence)) return false;
        String keyword = "DELETE".equals(plan.intent()) ? "(삭제|지워|지우|없애)" : "(그만먹|그만복용|복용중단|중단|오늘까지만)";
        if (!evidence.matches(".*" + keyword + ".*")
                || evidence.matches(".*(안|못)" + keyword + ".*")
                || evidence.matches(".*" + keyword + ".{0,6}(하지마|하지말|말고|말아|안해|취소).*")) return false;
        String later = users.substring(users.lastIndexOf(evidence) + evidence.length());
        if ("DELETE".equals(plan.intent()) && later.matches(".*(그만먹|그만복용|오늘까지만).*")) return false;
        return !later.matches(".*(취소|하지마|하지말|말아줘|안돼|그만해|관둬).*");
    }

    private int profileScore(String hint, MedicationTarget target) {
        String name = canonical(target.profileName());
        String relation = target.relationship() == null ? "" : target.relationship().toUpperCase(Locale.ROOT);
        if (name.equals(hint)) return 100;
        if (name.contains(hint)) return 90;
        if (List.of("엄마", "어머니", "모친").contains(hint)) {
            if (name.matches(".*(아빠|아버지|부친).*")) return 0;
            if (name.matches(".*(엄마|어머니|모친).*") || relation.equals("MOTHER")) return 80;
            if (relation.equals("PARENT")) return 40;
        }
        if (List.of("아빠", "아버지", "부친").contains(hint)) {
            if (name.matches(".*(엄마|어머니|모친).*")) return 0;
            if (name.matches(".*(아빠|아버지|부친).*") || relation.equals("FATHER")) return 80;
            if (relation.equals("PARENT")) return 40;
        }
        if (List.of("나", "내", "본인", "저").contains(hint) && relation.equals("SELF")) return 80;
        return 0;
    }

    private int drugScore(List<String> hints, String name) {
        int score = 0;
        for (String hint : hints) {
            int part = drugScore(hint, name);
            if (part == 0) return 0;
            score += part;
        }
        return score;
    }

    private int drugScore(String hint, String name) {
        String query = drugText(hint), candidate = drugText(name);
        var requiredNumbers = NUMBER.matcher(query).results().map(match -> match.group()).toList();
        var actualNumbers = NUMBER.matcher(candidate).results().map(match -> match.group()).toList();
        if (!actualNumbers.containsAll(requiredNumbers)) return 0;
        String letters = NUMBER.matcher(query).replaceAll("");
        String candidateLetters = NUMBER.matcher(candidate).replaceAll("");
        if (letters.isEmpty()) return requiredNumbers.isEmpty() ? 0 : 100;
        if (candidateLetters.contains(letters)) return 100;
        // 네 글자 이상 약 이름의 한 글자 차이만 허용한다. 함량은 위에서 정확히 일치해야 한다.
        if (letters.length() >= 4) {
            for (int start = 0; start + letters.length() <= candidateLetters.length(); start++) {
                int differences = 0;
                for (int i = 0; i < letters.length(); i++) if (letters.charAt(i) != candidateLetters.charAt(start + i)) differences++;
                if (differences == 1) return 60;
            }
        }
        return 0;
    }

    private String drugText(String value) {
        return canonical(value).replaceAll("밀리그램|밀리그람|마이크로그램|mcg|mg", "");
    }

    private String text(List<Map<String, Object>> transcript) {
        return canonical(transcript.stream().filter(part -> "user".equals(part.get("role")))
                .map(part -> (String) part.get("content")).collect(java.util.stream.Collectors.joining()));
    }

    private String canonical(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}.]", "");
    }
}
