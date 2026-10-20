package org.semiproject.pillbadeumserver.image.domain;

/** OCR/vision output used to search for matching pills. This is not a database entity. */
public record PillIdentification(
        String itemName,
        String entpName,
        String printFront,
        String printBack,
        String shape,
        String colorClass1,
        String colorClass2,
        String lineFront,
        String lineBack
) {
}
