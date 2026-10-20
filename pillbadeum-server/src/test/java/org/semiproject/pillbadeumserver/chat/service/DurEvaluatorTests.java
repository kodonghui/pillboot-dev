package org.semiproject.pillbadeumserver.chat.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.semiproject.pillbadeumserver.common.config.TimeZoneConfig;

import static org.assertj.core.api.Assertions.assertThat;

/** 합성 품목·사본으로 조회 범위와 금기 대조의 경계를 확인한다. 실제 의학적 판정 사례가 아니다. */
class DurEvaluatorTests {
    private final DurEvaluator evaluator = new DurEvaluator();
    private final LocalDate today = LocalDate.now(TimeZoneConfig.ZONE);

    @Test
    void noMedicationIsDifferentFromLookupFailureAndSuccessfulZeroRows() {
        var empty = evaluator.evaluate(input(26, "MALE", false, List.of()), List.of()).notice();
        assertThat(empty.lookup()).isEqualTo("NO_DRUG");
        assertThat(empty.result()).isNull();
        var input = input(26, "MALE", false, List.of(drug("A")));
        var failed = evaluator.evaluate(input, List.of()).notice();
        assertThat(failed.lookup()).isEqualTo("FAILED");
        assertThat(failed.result()).isNull();
        assertThat(failed.uncheckedDrugNames()).containsExactly("drug-A");
        var zero = evaluator.evaluate(input, complete(input)).notice();
        assertThat(zero.lookup()).isEqualTo("ALL");
        assertThat(zero.result()).isEqualTo("NONE");
        assertThat(zero.checkedCount()).isEqualTo(1);
        assertThat(zero.message()).contains("확인한 유형·프로필 조건");
    }

    @Test
    void aVerifiedDirectionFindsARegisteredPairEvenWhenTheOtherDrugLookupFails() {
        var input = input(26, "MALE", false, List.of(drug("A"), drug("B")));
        var results = new ArrayList<>(complete(input));
        results.removeIf(r -> r.itemSeq().equals("B"));
        results.add(withRows("A", DurLookup.Type.INTERACTION, row("B", "synthetic pair warning")));
        var notice = evaluator.evaluate(input, results).notice();
        assertThat(notice.lookup()).isEqualTo("PARTIAL");
        assertThat(notice.result()).isEqualTo("FOUND");
        assertThat(notice.checkedCount()).isEqualTo(1);
        assertThat(notice.uncheckedDrugNames()).containsExactly("drug-B");
        assertThat(notice.items()).hasSize(1);
        assertThat(notice.items().getFirst().type()).isEqualTo("INTERACTION");
        assertThat(notice.items().getFirst().drugNames()).containsExactly("drug-A", "drug-B");
    }

    @Test
    void cardPolicyKeepsWarningsAndDrugQuestionsButOmitsRepeatedGeneralGuidance() {
        var noDrug = new AnswerInput(1990, "MALE", false, "알림 설정은 어디에 있어?", List.of(), List.of(), false);
        var empty = evaluator.evaluate(noDrug, List.of()).notice();
        assertThat(evaluator.cardNotice(noDrug, empty)).isNull();
        var question = new AnswerInput(1990, "MALE", false, "약은 언제 먹어?", List.of(), List.of(), false);
        assertThat(evaluator.cardNotice(question, empty)).isSameAs(empty);
        var warning = new org.semiproject.pillbadeumserver.chat.dto.DurNotice("ALL", "FOUND", 1, List.of(), List.of(), "warning");
        assertThat(evaluator.cardNotice(noDrug, warning)).isSameAs(warning);
        var first = new AnswerInput(1990, "MALE", false, "약은 언제 먹어?", List.of(), List.of(), true);
        assertThat(evaluator.cardNotice(first, empty)).isNull();
        assertThat(evaluator.cardNotice(first, warning)).isSameAs(warning);
        var named = new AnswerInput(1990, "MALE", false, "drug-A는?", List.of(drug("A")), List.of(), false);
        assertThat(evaluator.cardNotice(named, empty)).isSameAs(empty);
    }

    @Test
    void reverseDuplicatesAreOnePairAndSimilarNamesOrUnregisteredPartnersDoNotMatch() {
        var input = input(26, "MALE", false, List.of(drug("A"), drug("B"), drug("A")));
        var results = new ArrayList<>(complete(input));
        results.add(withRows("A", DurLookup.Type.INTERACTION, row("B", "pair"), row("unregistered", "other")));
        results.add(withRows("B", DurLookup.Type.INTERACTION, row("A", "pair")));
        var evaluated = evaluator.evaluate(input, results);
        assertThat(evaluated.notice().checkedCount()).isEqualTo(2);
        assertThat(evaluated.notice().items()).hasSize(1);
        assertThat(evaluated.sources()).hasSize(2);
        var single = input(26, "MALE", false, List.of(drug("A")));
        assertThat(evaluator.evaluate(single, List.of(withRows("A", DurLookup.Type.INTERACTION, row("B", "other")),
                zero("A", DurLookup.Type.AGE))).notice().result()).isEqualTo("NONE");
    }

    @ParameterizedTest
    @CsvSource({"11, 12세 미만, FOUND, false", "12, 12세 미만, FOUND, true", "13, 12세 미만, NONE, false",
            "13, 만 12세 이하, FOUND, true", "15, 12세 이하, NONE, false", "12, 12세 이상, FOUND, true",
            "2, 24개월 미만, FOUND, true", "4, 24개월 미만, NONE, false"})
    void simpleAgeConditionsUseABirthYearRange(int years, String text, String expected, boolean possible) {
        var input = input(years, "MALE", false, List.of(drug("A")));
        var results = new ArrayList<>(complete(input));
        results.add(withRows("A", DurLookup.Type.AGE, row(null, text)));
        var notice = evaluator.evaluate(input, results).notice();
        assertThat(notice.result()).isEqualTo(expected);
        if (possible) assertThat(notice.items().getFirst().content()).contains("해당할 수 있어요");
    }

    @Test
    void complexAgeConditionsAndInvalidBirthYearsAreUnresolvedRatherThanNone() {
        for (String text : List.of("12세 미만 중 특정 질환 환자", "6세 이상 12세 미만", "threshold not available")) {
            var input = input(15, "MALE", false, List.of(drug("A")));
            var notice = evaluator.evaluate(input, List.of(withRows("A", DurLookup.Type.AGE, row(null, text)))).notice();
            assertThat(notice.lookup()).isEqualTo("ALL");
            assertThat(notice.result()).isNull();
            assertThat(notice.items().getFirst().content()).contains("자동으로 대조하지 못했어요");
        }
        var input = input(-1, "MALE", false, List.of(drug("A")));
        var results = new ArrayList<>(complete(input));
        results.add(withRows("A", DurLookup.Type.AGE, row(null, "12세 미만")));
        assertThat(evaluator.evaluate(input, results).notice().result()).isNull();
    }

    @Test
    void elderlyBoundaryIsConditionalAndYoungerProfilesDoNotGetAnElderlyWarning() {
        for (int years : List.of(64, 65, 66)) {
            var input = input(years, "MALE", false, List.of(drug("A")));
            var results = new ArrayList<>(complete(input));
            results.add(withRows("A", DurLookup.Type.ELDERLY, row(null, "synthetic elderly caution")));
            var notice = evaluator.evaluate(input, results).notice();
            assertThat(notice.result()).isEqualTo(years == 64 ? "NONE" : "FOUND");
            if (years == 65) assertThat(notice.items().getFirst().content()).contains("해당할 수 있어요");
        }
    }

    @Test
    void pregnancyTrueUsesTheSnapshotAndNullHasOnlyConditionalGuidance() {
        for (Boolean pregnant : new Boolean[]{true, false, null}) {
            var input = input(26, "FEMALE", pregnant, List.of(drug("A")));
            var results = new ArrayList<>(complete(input));
            results.add(withRows("A", DurLookup.Type.PREGNANCY, row(null, "synthetic pregnancy caution")));
            var notice = evaluator.evaluate(input, results).notice();
            assertThat(notice.result()).isEqualTo(Boolean.FALSE.equals(pregnant) ? "NONE" : "FOUND");
            if (pregnant == null) assertThat(notice.items().getFirst().content()).startsWith("임신 중이라면");
        }
        var male = input(26, "MALE", true, List.of(drug("A")));
        var results = new ArrayList<>(complete(male));
        results.add(withRows("A", DurLookup.Type.PREGNANCY, row(null, "must not be shown")));
        assertThat(evaluator.evaluate(male, results).notice().items()).isEmpty();
    }

    private List<DurLookup> complete(AnswerInput input) {
        var rows = new ArrayList<DurLookup>();
        for (var drug : input.medications()) for (var type : DurLookupService.requiredTypes(input,
                (int) input.medications().stream().map(AnswerInput.Medication::itemSeq).distinct().count(), today)) rows.add(zero(drug.itemSeq(), type));
        return rows;
    }
    private DurLookup zero(String code, DurLookup.Type type) { return new DurLookup(code, type, true, List.of(), today); }
    private DurLookup withRows(String code, DurLookup.Type type, DurLookup.Row... rows) {
        return new DurLookup(code, type, true, List.of(rows), today);
    }
    private DurLookup.Row row(String partner, String text) { return new DurLookup.Row(partner, "fixture", "fixture", text, today); }
    @Test
    void unidentifiedRegisteredDrugIsNotTreatedAsAnEmptyMedicationList() {
        var input = input(26, "MALE", false, List.of()).withUnidentifiedDrugNames(List.of("직접 입력 약"));
        var result = evaluator.evaluate(input, List.of()).notice();
        assertThat(result.lookup()).isEqualTo("FAILED");
        assertThat(result.result()).isNull();
        assertThat(result.checkedCount()).isZero();
        assertThat(result.uncheckedDrugNames()).containsExactly("직접 입력 약");
        assertThat(input.withSimilarSummaries(List.of()).unidentifiedDrugNames()).containsExactly("직접 입력 약");
    }

    @Test
    void successfulKnownDrugDoesNotHideAnUnidentifiedRegisteredDrug() {
        var input = input(26, "MALE", false, List.of(drug("A"))).withUnidentifiedDrugNames(List.of("직접 입력 약"));
        var rows = List.of(new DurLookup("A", DurLookup.Type.AGE, true, List.of(), today));
        var result = evaluator.evaluate(input, rows).notice();
        assertThat(result.lookup()).isEqualTo("PARTIAL");
        assertThat(result.result()).isNull();
        assertThat(result.checkedCount()).isEqualTo(1);
        assertThat(result.uncheckedDrugNames()).containsExactly("직접 입력 약");
    }

    private AnswerInput input(int years, String gender, Boolean pregnant, List<AnswerInput.Medication> drugs) {
        return new AnswerInput(today.getYear() - years, gender, pregnant, "복약 질문", drugs, List.of(), true);
    }
    private AnswerInput.Medication drug(String code) {
        return new AnswerInput.Medication(code, "drug-" + code, "maker", "OTC", 1, "식후", today, today,
                List.of(LocalTime.of(7, 0)), today);
    }
}
