package org.semiproject.pillbadeumserver.kakao.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** V3-KAKAO-012·013: 연결 코드의 글자·모양·받은 글 정리. 원문은 저장하지 않고 SHA-256만 둔다. */
class KakaoLinkCodesTests {

    @Test
    void newCodesUseOnlyClearLettersAndAlwaysHaveADigit() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2_000; i++) {
            String code = KakaoLinkCodes.generate();
            assertThat(code).hasSize(8).matches("[A-HJ-NP-Z2-9]{8}").containsPattern("[2-9]");
            assertThat(code).doesNotContain("0", "O", "1", "I");
            seen.add(code);
        }
        assertThat(seen).as("Random codes do not repeat in a small sample").hasSize(2_000);
    }

    @Test
    void displayAndReceivedFormsMeetInTheSameStoredCode() {
        assertThat(KakaoLinkCodes.display("7K3QM9XA")).isEqualTo("7K3Q-M9XA");
        for (String received : new String[]{"7K3Q-M9XA", "7k3q-m9xa", "  7K3QM9XA ", "7K3Q M9XA"}) {
            assertThat(KakaoLinkCodes.normalize(received)).as(received).contains("7K3QM9XA");
        }
    }

    @Test
    void anythingThatIsNotACodeIsEmpty() {
        for (String received : new String[]{null, "", "내 약", "7K3Q--M9XA", "7K3Q-M9X", "7K3Q-M9XAB", "7K3Q-M9X0",
                "7K3Q-M9XO", "7K3Q-M9X1", "7K3Q-M9XI", "7K3Q_M9XA", "7K3Q-M9X가"}) {
            assertThat(KakaoLinkCodes.normalize(received)).as(String.valueOf(received)).isEmpty();
        }
    }

    @Test
    void hashIsLowercaseSha256HexOfTheStoredForm() {
        assertThat(KakaoLinkCodes.hash("7K3QM9XA")).matches("[0-9a-f]{64}")
                .isEqualTo(KakaoLinkCodes.hash(KakaoLinkCodes.normalize("7k3q-m9xa").orElseThrow()))
                .isNotEqualTo(KakaoLinkCodes.hash("7K3QM9XB"));
    }
}
