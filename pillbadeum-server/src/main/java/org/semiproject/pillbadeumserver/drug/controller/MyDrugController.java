package org.semiproject.pillbadeumserver.drug.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.semiproject.pillbadeumserver.drug.dto.MyDrugCreateRequest;
import org.semiproject.pillbadeumserver.drug.dto.MyDrugDetailResponse;
import org.semiproject.pillbadeumserver.drug.dto.MyDrugListResponse;
import org.semiproject.pillbadeumserver.drug.service.MyDrugService;
import org.semiproject.pillbadeumserver.image.domain.PhotoDrugSelectionRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/* 설명. 내 약 API (/api/profiles/{profileId}/drugs)
 *  ----------------------------------------------------------------------------------
 *  로그인 필요. SecurityConfig 의 anyRequest().authenticated() 에 걸리므로 별도 permitAll 없음.
 *  프로필 소유권 확인 · 업무 규칙은 MyDrugService 가 맡고, 컨트롤러는 요청을 넘기고 DTO 를 돌려주기만 한다.
 *  ----------------------------------------------------------------------------------
 *  지금 구현된 API
 *  - GET    /api/profiles/{profileId}/drugs              내 약 목록
 *  - POST   /api/profiles/{profileId}/drugs              확인한 약 저장 (201, 저장된 상세 배열)
 *  - GET    /api/profiles/{profileId}/drugs/{myDrugId}   약 상세 (다른 프로필의 약이면 404)
 *  - DELETE /api/profiles/{profileId}/drugs/{myDrugId}   약 삭제 (204, 본문 없음)
 *  복용정보 수정은 범위에서 빠졌다. → 복약 일정 PUT /api/profiles/{profileId}/drugs/{myDrugId}/schedule
 * */
@Tag(name = "내 약", description = "프로필별 내 약 목록 · 상세 · 등록 · 삭제")
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

    /* 목차. 2. 확인한 약 저장 (이름 검색 · 알약 사진 · 처방전 사진 공통, 1~10개 한 번에) */
    @Operation(summary = "확인한 약 저장",
            description = "사용자가 확인한 약을 복용정보와 함께 저장합니다. 하나라도 실패하면 전부 저장되지 않습니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "items 방식은 saved/skipped, 기존 drugs 방식은 저장된 약 상세 배열임. 같은 식별 결과 재전송은 최초 응답을 재사용함.",
                    content = @Content(mediaType = "application/json", schema = @Schema(oneOf = {
                            PhotoDrugSelectionRequest.Result.class, MyDrugDetailResponse[].class}))),
            @ApiResponse(responseCode = "200", description = "items 방식에서 전부 이미 등록돼 건너뛴 경우임.",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = PhotoDrugSelectionRequest.Result.class)))
    })
    @PostMapping
    public ResponseEntity<?> register(@PathVariable Long profileId,
                                               @Valid @RequestBody MyDrugCreateRequest request) {
        if (request.items() != null) {
            var result = myDrugService.registerSelected(profileId,
                    new PhotoDrugSelectionRequest(request.source(), request.photoId(), request.items(), request.identificationId()));
            return ResponseEntity.status(result.saved().isEmpty() ? HttpStatus.OK : HttpStatus.CREATED).body(result);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(myDrugService.registerAll(profileId, request));
    }

    /* 목차. 3. 내 약 상세 (복용정보 + 약 정보, 근거 출처는 GET /api/drugs/{itemSeq}/guide) */
    @Operation(summary = "내 약 상세",
            description = "복용정보와 약 정보(모양 · 색 · 각인 · 전문/일반 · 성분명 · 출처)를 돌려줍니다. 이 프로필의 약이 아니면 404 입니다.")
    @GetMapping("/{myDrugId}")
    public MyDrugDetailResponse getMyDrug(@PathVariable Long profileId,
                                          @PathVariable Long myDrugId) {
        return myDrugService.getMyDrug(profileId, myDrugId);
    }

    /* 목차. 4. 내 약 삭제 (약과 그 복용정보 · 일정이 함께 없어진다. 일정만 지울 때는 DELETE .../schedule) */
    @Operation(summary = "내 약 삭제",
            description = "내 약 한 건을 삭제하고 204 를 돌려줍니다. 이 프로필의 약이 아니거나 이미 없으면 404 입니다.")
    @DeleteMapping("/{myDrugId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMyDrug(@PathVariable Long profileId,
                             @PathVariable Long myDrugId) {
        myDrugService.deleteMyDrug(profileId, myDrugId);
    }
}
