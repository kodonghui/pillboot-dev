package org.semiproject.pillbadeumserver.drug.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 2026-11-08 카톡 기록의 잘못 읽은·잘못 쓴 이름(한 글자 모음 차이, 첫 글자 차이, 함량 단위 '미리'·'g')을 가상 제품 이름으로
 * 재현해 비슷한 이름 판단을 확인한다. 실제 제품·환자 정보는 쓰지 않는다.
 */
class DrugNameSimilarityTests {
    @Test
    void keyDropsStrengthParenthesesAndSpacingAndReadsCapsuleSpellings() {
        assertThat(DrugNameSimilarity.key("가상뮤코스텐캡슐 200미리")).isEqualTo("가상뮤코스텐캡슐");
        assertThat(DrugNameSimilarity.key("가상유코스텐스캡슐20g")).isEqualTo("가상유코스텐스캡슐");
        assertThat(DrugNameSimilarity.key("가상룩펠정(가상성분) 5밀리그램")).isEqualTo("가상룩펠정");
        assertThat(DrugNameSimilarity.key("가상캅셀 10mg")).isEqualTo("가상캡슐");
    }

    @Test
    void oneVowelOrFirstLetterDifferenceIsSimilarButADifferentProductIsNot() {
        assertThat(DrugNameSimilarity.similarity("록펠정", "룩펠정")).isGreaterThan(0.85);
        assertThat(DrugNameSimilarity.similarity("가상유코스텐스캡슐", "가상뮤코스텐캡슐200밀리그램(가상성분)"))
                .isGreaterThanOrEqualTo(DrugNameSimilarity.SIMILAR);
        assertThat(DrugNameSimilarity.similarity("벨토라정", "가리몬정")).isLessThan(DrugNameSimilarity.SIMILAR);
        assertThat(DrugNameSimilarity.similarity("", "가상정")).isZero();
    }

    @Test
    void aCodeMatchIsTrustedOnlyWhenTheReadNameAgreesWithTheOfficialName() {
        assertThat(DrugNameSimilarity.consistent("", "가상룩펠정")).isTrue();
        assertThat(DrugNameSimilarity.consistent("가상룩펠정", "가상룩펠정(가상성분)")).isTrue();
        assertThat(DrugNameSimilarity.consistent("가상록펠정", "가상룩펠정")).isTrue();
        assertThat(DrugNameSimilarity.consistent("벨토라정", "가리몬정10밀리그램")).isFalse();
        assertThat(DrugNameSimilarity.consistent("가상정", "")).isFalse();
    }

    // PR #77 Codex 리뷰: 이름 비교가 함량을 빼고 해서 코드가 다른 함량 품목을 가리켜도 맞다고 봤음
    @Test
    void aCodeMatchWithAnotherWrittenStrengthIsNotTrusted() {
        assertThat(DrugNameSimilarity.consistent("가상룩펠정5mg", "가상룩펠정10mg")).isFalse();
        assertThat(DrugNameSimilarity.strengthConflict("가상룩펠정 5mg", "가상룩펠정10밀리그램")).isTrue();
        assertThat(DrugNameSimilarity.consistent("가상룩펠정 5mg", "가상룩펠정5밀리그램")).as("단위 표기만 다름").isTrue();
        assertThat(DrugNameSimilarity.consistent("가상룩펠정", "가상룩펠정10mg")).as("함량을 적지 않으면 이름만 봄").isTrue();
        assertThat(DrugNameSimilarity.strengthConflict("640000001", "가상룩펠정10mg")).as("코드 숫자는 함량이 아님").isFalse();
    }
}
