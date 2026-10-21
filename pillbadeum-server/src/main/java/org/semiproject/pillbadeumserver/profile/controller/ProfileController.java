package org.semiproject.pillbadeumserver.profile.controller;


import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.semiproject.pillbadeumserver.profile.dto.ProfileCreateRequest;
import org.semiproject.pillbadeumserver.profile.dto.ProfileResponse;
import org.semiproject.pillbadeumserver.profile.dto.ProfileUpdateRequest;
import org.semiproject.pillbadeumserver.profile.service.ProfileService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "프로필", description = "회원별 프로필 생성·목록 조회·수정·삭제")
@RestController
@RequestMapping("/api/profiles")
public class ProfileController {

    private final ProfileService profileService;

    @Autowired
    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    /* 프로필 생성 */
    @Operation(summary = "프로필 생성")
    @ApiResponse(responseCode = "201", description = "프로필 생성 완료")
    @PostMapping
    public ResponseEntity<ProfileResponse> createProfiles(
                                            @Valid @RequestBody ProfileCreateRequest request) {
        ProfileResponse response = profileService.createProfile(request);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /* 프로필 목록 조회 */
    @Operation(summary = "프로필 목록 조회")
    @GetMapping
    public ResponseEntity<List<ProfileResponse>> getAllProfiles() {
        return ResponseEntity.ok(profileService.getProfiles());
    }

    /* 프로필 수정 */
    @Operation(summary = "프로필 수정")
    @PutMapping("/{profileId}")
    public ResponseEntity<ProfileResponse> updateProfile(
                                        @PathVariable("profileId") Long profileId,
                                        @Valid @RequestBody ProfileUpdateRequest request) {

        return ResponseEntity.ok(profileService.updateProfile(profileId, request));

    }

    /* 프로필 삭제 */
    @Operation(summary = "프로필 삭제")
    @ApiResponse(responseCode = "204", description = "프로필 삭제 완료")
    @DeleteMapping("/{profileId}")
    public ResponseEntity<Void> deleteProfile (@PathVariable("profileId") Long profileId) {

        profileService.deleteProfile(profileId);

        return ResponseEntity.noContent().build();
    }
}
