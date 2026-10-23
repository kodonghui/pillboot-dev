package org.semiproject.pillbadeumserver.image.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class PillOcrVocabularyTests {
    @Test void providerSchemaAcceptsMissingValuesButOnlyOfficialShapeAndColorStrings() {
        var schema = new JsonMapper().valueToTree(PillOcrRules.schema());
        var shape = schema.path("properties").path("shape").path("enum");
        var color = schema.path("properties").path("colorClass1").path("enum");
        assertThat(shape).anyMatch(v -> v.isNull());
        assertThat(color).anyMatch(v -> v.isNull());
        assertThat(shape).anyMatch(v -> "육각형".equals(v.asString("")))
            .noneMatch(v -> "hexagonal".equals(v.asString("")));
        assertThat(color).anyMatch(v -> "연두".equals(v.asString("")))
            .noneMatch(v -> "light green".equals(v.asString("")));
        assertThat(schema.path("properties").path("colorClass2")).isEqualTo(schema.path("properties").path("colorClass1"));
    }
}
