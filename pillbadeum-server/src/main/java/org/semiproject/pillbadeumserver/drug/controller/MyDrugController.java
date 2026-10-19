package org.semiproject.pillbadeumserver.drug.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.semiproject.pillbadeumserver.drug.dto.MyDrugListResponse;
import org.semiproject.pillbadeumserver.drug.service.MyDrugService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/* 설명. 내 약 API (/api/profiles/{profileId}/drugs)
 *  ----------------------------------------------------------------------------------
 *  로그인 필요. SecurityConfig 의 anyRequest().authenticated() 에 걸리므로 별도 permitAll 없음.
 *  프로필 소유권 확인 · 업무 규칙은 MyDrugService 가 맡고, 컨트롤러는 요청을 넘기고 DTO 를 돌려주기만 한다.
 *  ----------------------------------------------------------------------------------
 *  지금 구현된 API
 *  - GET    /api/profiles/{profileId}/drugs              내 약 목록
 *  이후 추가할 API
 *  - POST   /api/profiles/{profileId}/drugs              확인한 약 저장 (201)
 *  - GET    /api/profiles/{profileId}/drugs/{myDrugId}   약 상세
 *  - PATCH  /api/profiles/{profileId}/drugs/{myDrugId}   복용정보 수정
 *  - DELETE /api/profiles/{profileId}/drugs/{myDrugId}   약 삭제 (204)
 * */
@Tag(name = "내 약", description = "프로필별 내 약 목록 · 상세 · 등록 · 수정 · 삭제")
@RestController
@RequestMapping("/api/profiles/{profileId}/drugs")
public class MyDrugController {

    private final MyDrugService myDrugService;

    public MyDrugController(MyDrugService myDrugService) {
        this.myDrugService = myDrugService;
    }

    /* 목차. 1. 내 약 목록 (최근 등록순, 없으면 빈 배열) */
    @Operation(summary = "내 약 목록", description = "프로필에 등록된 약을 최근 등록순으로 돌려줍니다.")
    @GetMapping
    public List<MyDrugListResponse> getMyDrugs(@PathVariable Long profileId) {
        return myDrugService.getMyDrugs(profileId);
    }
}
