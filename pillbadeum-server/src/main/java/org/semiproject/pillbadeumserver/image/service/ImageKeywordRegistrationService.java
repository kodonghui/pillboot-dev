package org.semiproject.pillbadeumserver.image.service;

import org.semiproject.pillbadeumserver.common.security.SecurityUtil;
import org.semiproject.pillbadeumserver.drug.service.ProfileAccessChecker;
import org.semiproject.pillbadeumserver.image.domain.ImagePhotoKind;
import org.semiproject.pillbadeumserver.image.domain.PillIdentification;
import org.semiproject.pillbadeumserver.image.domain.PillIdentificationCandidate;
import org.semiproject.pillbadeumserver.image.domain.PrescriptionPhotoIdentificationResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Saves image extraction keywords in the existing photo and ocr_candidate tables. */
@Service
public class ImageKeywordRegistrationService {

    private static final String INSERT_PHOTO_SQL = """
            INSERT INTO photo
                (member_id, profile_id, kind, side, temp_path, mime_type, size_bytes, status, uploaded_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String INSERT_CANDIDATE_SQL = """
            INSERT INTO ocr_candidate
                (photo_id, read_no, read_name, candidate_rank, lookup_status, print_matched)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ProfileAccessChecker profileAccessChecker;

    public ImageKeywordRegistrationService(JdbcTemplate jdbcTemplate,
                                           ProfileAccessChecker profileAccessChecker) {
        this.jdbcTemplate = jdbcTemplate;
        this.profileAccessChecker = profileAccessChecker;
    }

    @Transactional
    public List<Long> registerPrescriptionKeywords(
            Long profileId,
            ImagePhotoKind kind,
            List<MultipartFile> images,
            List<String> savedNames,
            List<PrescriptionPhotoIdentificationResponse.MedicineCandidates> medicines) {
        profileAccessChecker.checkOwner(profileId);
        Long memberId = SecurityUtil.getCurrentMemberId();
        List<Long> photoIds = new ArrayList<>(images.size());

        for (int i = 0; i < images.size(); i++) {
            MultipartFile image = images.get(i);
            Long photoId = insertPhoto(memberId, profileId, kind, null, image, savedNames.get(i));
            photoIds.add(photoId);
            // The extraction combines pages. Store its medicine names once under the first page.
            if (i == 0) {
                int readNo = 1;
                for (var medicine : medicines) {
                    saveKeywordCandidates(photoId, readNo++, medicine.readName(),
                            medicine.candidates(), medicine.lookupStatus());
                }
            }
        }
        return List.copyOf(photoIds);
    }

    @Transactional
    public List<Long> registerPillKeywords(
            Long profileId,
            List<MultipartFile> images,
            List<String> savedNames,
            PillIdentification extracted,
            List<PillIdentificationCandidate> candidates,
            String lookupStatus) {
        if (profileId != null) {
            profileAccessChecker.checkOwner(profileId);
        }
        Long memberId = SecurityUtil.getCurrentMemberId();
        List<Long> photoIds = new ArrayList<>(images.size());

        for (int i = 0; i < images.size(); i++) {
            String side = i == 0 ? "FRONT" : "BACK";
            Long photoId = insertPhoto(memberId, profileId, ImagePhotoKind.PILL, side,
                    images.get(i), savedNames.get(i));
            photoIds.add(photoId);

            // One extraction covers the front/back image set, so store its keywords once.
            if (i == 0) {
                for (String keyword : pillKeywords(extracted)) {
                    saveKeywordCandidates(photoId, 1, keyword, candidates, lookupStatus);
                }
            }
        }
        return List.copyOf(photoIds);
    }

    private Long insertPhoto(Long memberId, Long profileId, ImagePhotoKind kind, String side,
                             MultipartFile image, String savedName) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    INSERT_PHOTO_SQL, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, memberId);
            if (profileId == null) {
                statement.setNull(2, Types.BIGINT);
            } else {
                statement.setLong(2, profileId);
            }
            statement.setString(3, kind.name());
            statement.setString(4, side);
            statement.setString(5, savedName);
            statement.setString(6, image.getContentType());
            statement.setLong(7, image.getSize());
            statement.setString(8, "IDENTIFIED");
            statement.setObject(9, LocalDateTime.now());
            return statement;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("photo_id was not generated");
        }
        return key.longValue();
    }

    private void saveKeywordCandidates(Long photoId, int readNo, String readName,
                                       List<?> candidates, String lookupStatus) {
        if (readName == null || readName.isBlank() || readName.length() > 50) {
            return;
        }

        if ("OK".equals(lookupStatus) && candidates != null && !candidates.isEmpty()) {
            for (int index = 0; index < candidates.size(); index++) {
                jdbcTemplate.update(INSERT_CANDIDATE_SQL, photoId, readNo, readName,
                        index + 1, "OK", null);
            }
            return;
        }

        String status = "FAILED".equals(lookupStatus) ? "FAILED" : "NOT_FOUND";
        jdbcTemplate.update(INSERT_CANDIDATE_SQL, photoId, readNo, readName, 1, status, null);
    }

    private static List<String> pillKeywords(PillIdentification extracted) {
        Set<String> keywords = new LinkedHashSet<>();
        addKeyword(keywords, extracted.itemName());
        addKeyword(keywords, extracted.printFront());
        addKeyword(keywords, extracted.printBack());
        return List.copyOf(keywords);
    }

    private static void addKeyword(Set<String> keywords, String value) {
        if (value != null) {
            String keyword = value.strip();
            if (!keyword.isEmpty() && keyword.length() <= 50) {
                keywords.add(keyword);
            }
        }
    }
}
