package org.semiproject.pillbadeumserver.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Map;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.semiproject.pillbadeumserver.common.external.PublicDataClient;
import org.semiproject.pillbadeumserver.image.service.PhotoCandidateLookup;
import org.semiproject.pillbadeumserver.image.service.PillCatalog;
import tools.jackson.databind.json.JsonMapper;

/** 명시적으로 켠 경우만 실제 공공데이터를 조회한다. DB·OpenAI·사용자 사진은 사용하지 않는다. */
@EnabledIfEnvironmentVariable(named = "PUBLIC_DATA_LIVE_TEST", matches = "true")
class PrescriptionLookupLiveTests {
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "슈가메트서방정5/100... | 201508553",
        "듀비메트서방정0.5/10... | 201604112",
        "듀비메트서방정0.5/10… | 201604112",
        "슈가메트서방정5/1000밀리그램 | 201508553",
        "듀비메트서방정0.5/1000밀리그램 | 201604112"
    })
    void actualOfficialLookupReturnsTheExpectedCandidate(String readName, String expectedCode) {
        var json = new JsonMapper();
        var client = new PublicDataClient(json, System.getenv("PUBLIC_DATA_BASE_URL"),
                System.getenv("PUBLIC_DATA_SERVICE_KEY"), 3000, 15000, 4, 10000);
        var result = new PhotoCandidateLookup(client, mock(PillCatalog.class)).prescriptionResult(readName, null);
        assertThat(result.candidates()).anyMatch(candidate -> expectedCode.equals(candidate.itemSeq()));
        System.out.println(json.writeValueAsString(Map.of("readName", readName, "status", result.status(),
                "candidates", result.candidates().stream().map(candidate ->
                        Map.of("itemSeq", candidate.itemSeq(), "name", candidate.name())).toList())));
    }
}
