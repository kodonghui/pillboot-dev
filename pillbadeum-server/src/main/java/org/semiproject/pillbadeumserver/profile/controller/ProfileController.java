package org.semiproject.pillbadeumserver.profile.controller;


import jakarta.validation.Valid;
import org.semiproject.pillbadeumserver.profile.dto.ProfileCreateRequest;
import org.semiproject.pillbadeumserver.profile.dto.ProfileResponse;
import org.semiproject.pillbadeumserver.profile.service.ProfileService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/profiles")
public class ProfileController {

    private final ProfileService profileService;

    @Autowired
    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    /* 프로필 생성 */
    @PostMapping
    public ResponseEntity<ProfileResponse> createProfiles(
                                            @Valid @RequestBody ProfileCreateRequest request) {
        ProfileResponse response = profileService.createProfile(request);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /* 프로필 조회 */
    @GetMapping
    public ResponseEntity<List<ProfileResponse>> getAllProfiles() {
        return ResponseEntity.ok(profileService.getProfiles());
    }
}
