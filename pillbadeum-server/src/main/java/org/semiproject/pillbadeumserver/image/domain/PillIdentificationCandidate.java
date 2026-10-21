package org.semiproject.pillbadeumserver.image.domain;

/** Public-data details for one pill candidate. This is not a database entity. */
public record PillIdentificationCandidate(
        int rank,
        Double similarityScore,
        String itemSeq,
        String itemName,
        String entpName,
        String itemImage,
        String printFront,
        String printBack,
        String drugShape,
        String colorClass1,
        String colorClass2,
        String lineFront,
        String lineBack,
        String etcOtcName,
        String className,
        String ingredientName,
        String permitDate,
        String efficacy,
        String useMethod,
        String warning,
        String precautions,
        String interactions,
        String sideEffects,
        String storageMethod,
        String orientation,
        Integer evidenceRank,
        java.util.List<PillMatchDetail> matchDetails
) {
}
