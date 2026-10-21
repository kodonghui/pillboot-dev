package org.semiproject.pillbadeumserver.image.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;
import org.semiproject.pillbadeumserver.drug.domain.DrugSource;

/** REG-007 v1의 추가 필드임. 처방전 응답에는 포함하지 않음. */
public record PillIdentificationDetails(
    int contractVersion, String identificationId,
    @Schema(allowableValues = {"ACTIVE"}) String lifecycle, String origin,
    DrugSource registrationSource, OffsetDateTime expiresAt, String reason,
    int totalCandidateCount, int returnedCandidateCount, int candidateLimit,
    boolean hasMoreCandidates, boolean hasTiedCandidates, boolean requiresRefinement,
    boolean selectionAllowed, List<String> missingFields, List<String> nextActions) {}
