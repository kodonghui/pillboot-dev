package org.semiproject.pillbadeumserver.drug;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.semiproject.pillbadeumserver.common.config.CorsConfig;
import org.semiproject.pillbadeumserver.common.config.WebConfig;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.common.exception.ForbiddenException;
import org.semiproject.pillbadeumserver.common.exception.GlobalExceptionHandler;
import org.semiproject.pillbadeumserver.common.exception.NotFoundException;
import org.semiproject.pillbadeumserver.common.security.JwtAccessDeniedHandler;
import org.semiproject.pillbadeumserver.common.security.JwtAuthenticationEntryPoint;
import org.semiproject.pillbadeumserver.common.security.JwtTokenProvider;
import org.semiproject.pillbadeumserver.common.security.SecurityConfig;
import org.semiproject.pillbadeumserver.drug.controller.MyDrugController;
import org.semiproject.pillbadeumserver.drug.domain.DrugSource;
import org.semiproject.pillbadeumserver.drug.domain.MyDrug;
import org.semiproject.pillbadeumserver.drug.dto.MyDrugCreateRequest;
import org.semiproject.pillbadeumserver.drug.dto.MyDrugDetailResponse;
import org.semiproject.pillbadeumserver.drug.dto.MyDrugListResponse;
import org.semiproject.pillbadeumserver.drug.service.MyDrugService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/* 설명. 내 약 API (MyDrugController) 의 요청 검증 · 응답 · 상태 코드를 확인한다.
 *  서비스는 가짜(mock)로 바꾸므로 DB 를 쓰지 않는다. 실제 보안 필터와 공통 오류 처리는 그대로 쓴다.
 * */
@WebMvcTest(controllers = MyDrugController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, CorsConfig.class, WebConfig.class, GlobalExceptionHandler.class})
class MyDrugWebTests {

    private static final String ITEM_SEQ = "202106092";

    @Autowired MockMvc mvc;
    @Autowired JwtTokenProvider tokens;
    @MockitoBean MyDrugService service;

    /* 목차. 1. 목록 */
    @Test
    void listReturnsMyDrugsIncludingManuallyNamedOnes() throws Exception {
        when(service.getMyDrugs(2L)).thenReturn(List.of(
                MyDrugListResponse.from(searched()),
                MyDrugListResponse.from(MyDrug.createCustom(2L, "혈압약"))));

        mvc.perform(get("/api/profiles/2/drugs").header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].itemSeq").value(ITEM_SEQ))
                .andExpect(jsonPath("$[0].doseTimes[0]").value("08:00"))
                .andExpect(jsonPath("$[1].name").value("혈압약"))
                .andExpect(jsonPath("$[1].source").value("MANUAL"));
    }

    @Test
    void foreignOrMissingProfileUsesCommonErrorCodes() throws Exception {
        when(service.getMyDrugs(4L)).thenThrow(new ForbiddenException());
        when(service.getMyDrugs(99999L)).thenThrow(new NotFoundException("존재하지 않는 프로필입니다."));

        mvc.perform(get("/api/profiles/4/drugs").header("Authorization", auth()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/profiles/99999/drugs").header("Authorization", auth()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /* 목차. 2. 저장 */
    @Test
    void registerReturns201WithSavedDetailsAndPassesTheArrayRequest() throws Exception {
        when(service.registerAll(eq(2L), any(MyDrugCreateRequest.class)))
                .thenReturn(List.of(MyDrugDetailResponse.from(searched())));
        ArgumentCaptor<MyDrugCreateRequest> request = ArgumentCaptor.forClass(MyDrugCreateRequest.class);

        mvc.perform(post("/api/profiles/2/drugs").header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON).content(body(item(ITEM_SEQ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("타이레놀정500밀리그램"))
                .andExpect(jsonPath("$[0].doseTimes[0]").value("08:00"))
                .andExpect(jsonPath("$[0].sourceName").value("이름 검색"))
                .andExpect(jsonPath("$[0].drug.itemSeq").value(ITEM_SEQ));

        verify(service).registerAll(eq(2L), request.capture());
        assertThat(request.getValue().source()).isEqualTo(DrugSource.SEARCH);
        assertThat(request.getValue().drugs()).hasSize(1);
        assertThat(request.getValue().drugs().get(0).itemSeq()).isEqualTo(ITEM_SEQ);
        assertThat(request.getValue().drugs().get(0).doseTimes()).containsExactly(LocalTime.of(8, 0), LocalTime.of(20, 0));
    }

    @Test
    void invalidRegisterBodiesAreRejectedBeforeTheService() throws Exception {
        List<String> bodies = new ArrayList<>(List.of(
                "{\"drugs\":[" + item(ITEM_SEQ) + "]}",                                          // source 없음
                "{\"source\":\"SEARCH\",\"drugs\":[]}",                                           // 약 0개
                body(item(ITEM_SEQ).replace("\"dosePerTime\":1", "\"dosePerTime\":0")),         // 1회 개수 0
                body(item(ITEM_SEQ).replace("\"dosePerTime\":1", "\"dosePerTime\":11")),        // 1회 개수 11 (최대 10)
                body(item(ITEM_SEQ).replace("식후 30분", "가".repeat(21))),                       // 복용 시점 21자 (최대 20)
                body(item("abc")),                                                                // 품목코드 숫자 아님
                body(item(ITEM_SEQ).replace("\"startDate\":\"2026-10-20\",", "")),               // 시작일 없음
                body(item(ITEM_SEQ).replace("[\"08:00\",\"20:00\"]", "[\"07:00\",\"09:00\",\"12:00\",\"18:00\"]")), // 시각 4개
                "{\"source\":\"WRONG\",\"drugs\":[" + item(ITEM_SEQ) + "]}",                     // 없는 등록 방법
                "{\"source\":\"SEARCH\",\"drugs\":["                                              // JSON 깨짐
        ));
        StringBuilder eleven = new StringBuilder();
        for (int i = 0; i < 11; i++) {
            eleven.append(i == 0 ? "" : ",").append(item("20210609" + i));
        }
        bodies.add("{\"source\":\"SEARCH\",\"drugs\":[" + eleven + "]}");                    // 약 11개 (최대 10)

        for (String invalid : bodies) {
            mvc.perform(post("/api/profiles/2/drugs").header("Authorization", auth())
                            .contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        verifyNoInteractions(service);
    }

    @Test
    void alreadyRegisteredDrugIsConflict() throws Exception {
        when(service.registerAll(eq(2L), any(MyDrugCreateRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.DUPLICATE, "이미 등록된 약입니다. (itemSeq=" + ITEM_SEQ + ")"));

        mvc.perform(post("/api/profiles/2/drugs").header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON).content(body(item(ITEM_SEQ))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    /* 목차. 3. 상세 */
    @Test
    void detailOfManuallyNamedDrugHasNameAndNoDrugInfo() throws Exception {
        when(service.getMyDrug(2L, 35L)).thenReturn(MyDrugDetailResponse.from(MyDrug.createCustom(2L, "혈압약")));

        mvc.perform(get("/api/profiles/2/drugs/35").header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("혈압약"))
                .andExpect(jsonPath("$.drug").value(nullValue()))
                .andExpect(jsonPath("$.source").value("MANUAL"))
                .andExpect(jsonPath("$.doseTimes", hasSize(0)));
    }

    @Test
    void detailOfOtherProfilesDrugIsNotFoundAndNonNumericIdIsBadRequest() throws Exception {
        when(service.getMyDrug(2L, 36L)).thenThrow(new NotFoundException("등록된 약을 찾을 수 없습니다."));

        mvc.perform(get("/api/profiles/2/drugs/36").header("Authorization", auth()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(get("/api/profiles/2/drugs/abc").header("Authorization", auth()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /* 목차. 4. 삭제 */
    @Test
    void deleteReturns204WithoutBody() throws Exception {
        mvc.perform(delete("/api/profiles/2/drugs/35").header("Authorization", auth()))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        verify(service).deleteMyDrug(2L, 35L);
    }

    @Test
    void deleteErrorsUseCommonErrorCodes() throws Exception {
        doThrow(new NotFoundException("등록된 약을 찾을 수 없습니다.")).when(service).deleteMyDrug(2L, 99L);
        doThrow(new ForbiddenException()).when(service).deleteMyDrug(4L, 35L);

        mvc.perform(delete("/api/profiles/2/drugs/99").header("Authorization", auth()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(delete("/api/profiles/4/drugs/35").header("Authorization", auth()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(delete("/api/profiles/2/drugs/abc").header("Authorization", auth()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /* 목차. 5. 로그인 */
    @Test
    void everyMyDrugApiNeedsLogin() throws Exception {
        mvc.perform(get("/api/profiles/2/drugs")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(post("/api/profiles/2/drugs").contentType(MediaType.APPLICATION_JSON).content(body(item(ITEM_SEQ))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/profiles/2/drugs/35")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/profiles/2/drugs/35")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    private String auth() {
        return "Bearer " + tokens.createAccessToken(7L, "fixture@example.test", "ROLE_USER");
    }

    private static MyDrug searched() {
        return MyDrug.create(2L, DrugWebTests.drug(), DrugSource.SEARCH, 1, "식후 30분",
                LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 26),
                List.of(LocalTime.of(20, 0), LocalTime.of(8, 0)));
    }

    private static String item(String itemSeq) {
        return "{\"itemSeq\":\"" + itemSeq + "\",\"dosePerTime\":1,\"timing\":\"식후 30분\","
                + "\"startDate\":\"2026-10-20\",\"endDate\":\"2026-10-26\",\"doseTimes\":[\"08:00\",\"20:00\"]}";
    }

    private static String body(String item) {
        return "{\"source\":\"SEARCH\",\"drugs\":[" + item + "]}";
    }
}
