package org.semiproject.pillbadeumserver.image.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/** OCR/vision output used to search for matching pills. This is not a database entity. */
@Schema(description = "제품명·업체명은 없어도 됩니다. 판독하지 못한 단서는 null로 보냅니다.", example = """
        {"itemName":null,"entpName":null,"printFront":"DK","printBack":"10","shape":"원형","colorClass1":"흰색","colorClass2":null,"lineFront":null,"lineBack":null}
        """)
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
