package org.semiproject.pillbadeumserver.drug;

import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.common.exception.ForbiddenException;
import org.semiproject.pillbadeumserver.common.exception.NotFoundException;
import org.semiproject.pillbadeumserver.common.external.PublicDataClient;
import org.semiproject.pillbadeumserver.drug.domain.Drug;
import org.semiproject.pillbadeumserver.drug.domain.DrugRepository;
import org.semiproject.pillbadeumserver.drug.domain.DrugSource;
import org.semiproject.pillbadeumserver.drug.domain.DurWarningRepository;
import org.semiproject.pillbadeumserver.drug.domain.MyDrug;
import org.semiproject.pillbadeumserver.drug.domain.MyDrugRepository;
import org.semiproject.pillbadeumserver.drug.service.DrugGuideService;
import org.semiproject.pillbadeumserver.drug.service.DrugSearchService;
import org.semiproject.pillbadeumserver.drug.service.DrugSyncService;
import org.semiproject.pillbadeumserver.drug.service.ProfileAccessChecker;
import org.springframework.data.domain.PageRequest;

import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/* 설명. 잘못된 입력은 공공데이터 API 를 부르기 전에 400 으로 막는지, DB 에 있는 약은 API 를 다시 부르지 않는지 확인한다.
 *  (공공데이터 호출 수 · 응답 지연을 아끼기 위한 규칙)
 * */
class DrugInputValidationTests {

    private final PublicDataClient publicDataClient = mock(PublicDataClient.class);
    private final DrugRepository drugRepository = mock(DrugRepository.class);
    private final MyDrugRepository myDrugRepository = mock(MyDrugRepository.class);
    private final ProfileAccessChecker profileAccessChecker = mock(ProfileAccessChecker.class);

    private final DrugSearchService searchService = new DrugSearchService(publicDataClient, myDrugRepository, profileAccessChecker);
    private final DrugSyncService syncService = new DrugSyncService(publicDataClient, drugRepository);
    private final DrugGuideService guideService = new DrugGuideService(publicDataClient, drugRepository,
            mock(DurWarningRepository.class));

    /* 목차. 1. 이름 검색: 2~50자 */
    @Test
    void searchNameMustBeTwoToFiftyCharacters() {
        char[] tooLong = new char[DrugSearchService.MAX_NAME_LENGTH + 1];
        Arrays.fill(tooLong, '가');

        for (String name : new String[]{null, "", "  ", "부", " 부 ", new String(tooLong)}) {
            assertInvalidInput(() -> searchService.search(name, PageRequest.of(0, 10)));
        }
        verifyNoInteractions(publicDataClient);
    }

    @Test
    void searchForForeignProfileStopsBeforePublicData() {
        doThrow(new ForbiddenException()).when(profileAccessChecker).checkOwner(4L);

        assertThatThrownBy(() -> searchService.searchForProfile(4L, "타이레놀", PageRequest.of(0, 10)))
                .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(publicDataClient, myDrugRepository);
    }

    /* 목차. 2. 약 정보: 품목기준코드는 숫자만 */
    @Test
    void drugInfoRejectsNonNumericItemSeqBeforeAnyLookup() {
        for (String itemSeq : new String[]{null, "", "  ", "abc", "2021a6092", "-1"}) {
            assertInvalidInput(() -> syncService.getOrSync(itemSeq));
        }
        verifyNoInteractions(publicDataClient, drugRepository);
    }

    @Test
    void drugAlreadyInDatabaseIsReturnedWithoutCallingPublicData() {
        Drug saved = DrugWebTests.drug();
        when(drugRepository.findById("202106092")).thenReturn(Optional.of(saved));

        assertThat(syncService.getOrSync(" 202106092 ")).isSameAs(saved);
        verifyNoInteractions(publicDataClient);
    }

    /* 목차. 3. 근거 출처: 형식 오류 400, drug 테이블에 없으면 404 (공공데이터를 부르지 않음) */
    @Test
    void guideRejectsNonNumericItemSeqBeforeAnyLookup() {
        for (String itemSeq : new String[]{null, "", "abc", "2021a6092"}) {
            assertInvalidInput(() -> guideService.getGuide(itemSeq));
        }
        verifyNoInteractions(publicDataClient, drugRepository);
    }

    @Test
    void guideForDrugNotInDatabaseIsNotFoundWithoutCallingPublicData() {
        when(drugRepository.findById("999999999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> guideService.getGuide("999999999")).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(publicDataClient);
    }

    /* 목차. 4. 1회 복용량: 요청 검증이 없는 예전 경로로 들어와도 엔티티가 팀 명세 1~10을 지킴 */
    @Test
    void dosePerTimeAboveTenIsRejectedEvenWithoutRequestValidation() {
        Drug drug = DrugWebTests.drug();
        assertInvalidInput(() -> MyDrug.create(1L, drug, DrugSource.SEARCH, 11, null, null, null, null));
        MyDrug saved = MyDrug.create(1L, drug, DrugSource.SEARCH, 10, null, null, null, null);
        assertInvalidInput(() -> saved.changeDoseInfo(11, null, null, null, null));
        assertThat(saved.getDosePerTime()).isEqualTo(10);
    }

    private static void assertInvalidInput(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }
}
