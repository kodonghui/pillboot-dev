package org.semiproject.pillbadeumserver.drug;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.semiproject.pillbadeumserver.common.config.CorsConfig;
import org.semiproject.pillbadeumserver.common.config.WebConfig;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.common.exception.ExternalApiException;
import org.semiproject.pillbadeumserver.common.exception.ForbiddenException;
import org.semiproject.pillbadeumserver.common.exception.GlobalExceptionHandler;
import org.semiproject.pillbadeumserver.common.exception.NotFoundException;
import org.semiproject.pillbadeumserver.common.security.JwtAccessDeniedHandler;
import org.semiproject.pillbadeumserver.common.security.JwtAuthenticationEntryPoint;
import org.semiproject.pillbadeumserver.common.security.JwtTokenProvider;
import org.semiproject.pillbadeumserver.common.security.SecurityConfig;
import org.semiproject.pillbadeumserver.drug.controller.DrugController;
import org.semiproject.pillbadeumserver.drug.domain.Drug;
import org.semiproject.pillbadeumserver.drug.dto.DrugGuideResponse;
import org.semiproject.pillbadeumserver.drug.dto.DrugSearchResponse;
import org.semiproject.pillbadeumserver.drug.service.DrugGuideService;
import org.semiproject.pillbadeumserver.drug.service.DrugSearchService;
import org.semiproject.pillbadeumserver.drug.service.DrugSyncService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/* 설명. 약 검색 · 약 정보 · 근거 출처 API (DrugController) 의 요청 · 응답 · 상태 코드를 확인한다.
 *  서비스는 가짜(mock)로 바꾸므로 공공데이터 API · DB 를 부르지 않는다. 실제 보안 필터와 공통 오류 처리는 그대로 쓴다.
 * */
@WebMvcTest(controllers = DrugController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, CorsConfig.class, WebConfig.class, GlobalExceptionHandler.class})
class DrugWebTests {

    private static final String ITEM_SEQ = "202106092";

    @Autowired MockMvc mvc;
    @Autowired JwtTokenProvider tokens;
    @MockitoBean DrugSearchService searchService;
    @MockitoBean DrugSyncService syncService;
    @MockitoBean DrugGuideService guideService;

    /* 목차. 1. 이름 검색 */
    @Test
    void searchReturnsPageWithRegisteredFlagAndOneBasedPageNumber() throws Exception {
        when(searchService.searchForProfile(eq(2L), eq("타이레놀"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(DrugSearchResponse.of(ITEM_SEQ, "타이레놀정500밀리그램",
                        "한국존슨앤드존슨판매(유)", null, true)), PageRequest.of(0, 10), 1));

        mvc.perform(get("/api/drugs/search").param("name", "타이레놀").param("profileId", "2")
                        .header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].itemSeq").value(ITEM_SEQ))
                .andExpect(jsonPath("$.content[0].registered").value(true))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.number").value(1));
    }

    @Test
    void searchUsesTenPerPageByDefaultAndPassesRequestedSize() throws Exception {
        when(searchService.searchForProfile(eq(2L), eq("타이레놀"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);

        mvc.perform(get("/api/drugs/search").param("name", "타이레놀").param("profileId", "2")
                .header("Authorization", auth())).andExpect(status().isOk());
        mvc.perform(get("/api/drugs/search").param("name", "타이레놀").param("profileId", "2")
                .param("size", "20").header("Authorization", auth())).andExpect(status().isOk());

        verify(searchService, org.mockito.Mockito.times(2)).searchForProfile(eq(2L), eq("타이레놀"), pageable.capture());
        assertThat(pageable.getAllValues().get(0).getPageSize()).isEqualTo(DrugSearchService.DEFAULT_PAGE_SIZE);
        assertThat(pageable.getAllValues().get(1).getPageSize()).isEqualTo(20);
    }

    @Test
    void searchWithoutNameOrProfileIdIsRejectedBeforeTheService() throws Exception {
        mvc.perform(get("/api/drugs/search").param("profileId", "2").header("Authorization", auth()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(get("/api/drugs/search").param("name", "타이레놀").header("Authorization", auth()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(get("/api/drugs/search").param("name", "타이레놀").param("profileId", "abc")
                        .header("Authorization", auth()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(searchService);
    }

    @Test
    void searchServiceErrorsUseCommonErrorCodes() throws Exception {
        when(searchService.searchForProfile(eq(2L), eq("부"), any(Pageable.class)))
                .thenThrow(new BusinessException(ErrorCode.INVALID_INPUT, "약 이름을 2자 이상 입력해주세요."));
        when(searchService.searchForProfile(eq(4L), eq("타이레놀"), any(Pageable.class)))
                .thenThrow(new ForbiddenException());
        when(searchService.searchForProfile(eq(2L), eq("타이레놀"), any(Pageable.class)))
                .thenThrow(ExternalApiException.failed("e약은요", "fixture", null));

        mvc.perform(get("/api/drugs/search").param("name", "부").param("profileId", "2")
                        .header("Authorization", auth()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(get("/api/drugs/search").param("name", "타이레놀").param("profileId", "4")
                        .header("Authorization", auth()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/drugs/search").param("name", "타이레놀").param("profileId", "2")
                        .header("Authorization", auth()))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("EXTERNAL_API_FAILED"));
    }

    /* 목차. 2. 약 정보 */
    @Test
    void drugInfoReturnsShapeAndMaterial() throws Exception {
        when(syncService.getOrSync(ITEM_SEQ)).thenReturn(drug());

        mvc.perform(get("/api/drugs/{itemSeq}", ITEM_SEQ).header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemSeq").value(ITEM_SEQ))
                .andExpect(jsonPath("$.name").value("타이레놀정500밀리그램"))
                .andExpect(jsonPath("$.material").value("Acetaminophen"));
    }

    @Test
    void drugInfoErrorsUseCommonErrorCodes() throws Exception {
        when(syncService.getOrSync("202201300")).thenThrow(new NotFoundException("낱알식별 정보가 부족해 등록할 수 없는 약입니다."));
        when(syncService.getOrSync("abc")).thenThrow(new BusinessException(ErrorCode.INVALID_INPUT, "품목기준코드(itemSeq)를 확인해주세요."));

        mvc.perform(get("/api/drugs/{itemSeq}", "202201300").header("Authorization", auth()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(get("/api/drugs/{itemSeq}", "abc").header("Authorization", auth()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /* 목차. 3. 근거 출처 */
    @Test
    void guideReturnsSourceSectionsAndNotice() throws Exception {
        when(guideService.getGuide(ITEM_SEQ)).thenReturn(new DrugGuideResponse(ITEM_SEQ, "타이레놀정500밀리그램", true,
                DrugGuideService.EASY_DRUG_SOURCE, "타이레놀정500밀리그램", LocalDate.of(2025, 3, 24),
                LocalDateTime.of(2026, 10, 20, 10, 0),
                List.of(new DrugGuideResponse.Section("efficacy", "효능", "해열 및 진통")),
                List.of(), "식품의약품안전처 DUR", DrugGuideResponse.NOTICE));

        mvc.perform(get("/api/drugs/{itemSeq}/guide", ITEM_SEQ).header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.sourceName").value(DrugGuideService.EASY_DRUG_SOURCE))
                .andExpect(jsonPath("$.sourceItemName").value("타이레놀정500밀리그램"))
                .andExpect(jsonPath("$.sections", hasSize(1)))
                .andExpect(jsonPath("$.sections[0].title").value("효능"))
                .andExpect(jsonPath("$.durWarnings", hasSize(0)))
                .andExpect(jsonPath("$.notice").value(DrugGuideResponse.NOTICE));
    }

    @Test
    void guideForDrugNotInDatabaseIsNotFound() throws Exception {
        when(guideService.getGuide("999999999")).thenThrow(new NotFoundException("약 정보를 찾을 수 없습니다."));

        mvc.perform(get("/api/drugs/{itemSeq}/guide", "999999999").header("Authorization", auth()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /* 목차. 4. 로그인 */
    @Test
    void everyDrugApiNeedsLogin() throws Exception {
        mvc.perform(get("/api/drugs/search").param("name", "타이레놀").param("profileId", "2"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/drugs/{itemSeq}", ITEM_SEQ)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/drugs/{itemSeq}/guide", ITEM_SEQ)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/drugs/{itemSeq}", ITEM_SEQ).header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(searchService, syncService, guideService);
    }

    private String auth() {
        return "Bearer " + tokens.createAccessToken(7L, "fixture@example.test", "ROLE_USER");
    }

    static Drug drug() {
        return Drug.create(ITEM_SEQ, "타이레놀정500밀리그램", "한국존슨앤드존슨판매(유)", Drug.OTC, "해열.진통.소염제",
                null, "TYLENOL", "500", "장방형", "하양", "-", "-", "정제", "Acetaminophen",
                LocalDateTime.of(2026, 10, 20, 10, 0));
    }
}
