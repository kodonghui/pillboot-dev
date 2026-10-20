package org.semiproject.pillbadeumserver.medication.service;

import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.common.ai.OpenAiClient;
import org.semiproject.pillbadeumserver.medication.domain.MedicationTarget;
import org.semiproject.pillbadeumserver.medication.dto.MedicationLiveDtos.StartRequest;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MedicationLivePlannerTests {
    @Test
    void optionalMedicationIdIsSerializedAsNullAndCustomNameIsPartOfTheSchema() {
        var json = new ObjectMapper();
        var ai = mock(OpenAiClient.class);
        var planner = new MedicationLivePlanner(ai, json, "fixture-model");
        when(ai.createJson(anyString(), anyList(), anyInt(), anyString(), anyMap())).thenAnswer(call -> {
            var messages = call.<List<org.semiproject.pillbadeumserver.common.ai.AiMessage>>getArgument(1);
            var input = json.readTree(messages.getFirst().content());
            assertThat(input.path("selectedMyDrugId").isNull()).isTrue();
            assertThat(input.path("profiles").size()).isEqualTo(1);
            var schema = json.valueToTree(call.<Map<String, Object>>getArgument(4));
            assertThat(schema.path("required").toString()).contains("customName", "profileId");
            return json.valueToTree(Map.of("intent", "SAVE", "customName", "혈압약", "useDefaults", false));
        });
        var plan = planner.plan(new StartRequest("offer", 17L, null, LocalDate.of(2026, 10, 20)),
                LocalDate.of(2026, 10, 20), List.of(),
                List.of(new org.semiproject.pillbadeumserver.medication.dto.MedicationDtos.ProfileChoice(17, "엄마", "MOTHER")),
                List.of(Map.of("role", "user", "content", "혈압약 등록해줘")));
        assertThat(plan.customName()).isEqualTo("혈압약");
        assertThat(plan.myDrugId()).isNull();
    }

    @Test
    void removalOutputParsesTheSpokenHintsAndReceivesScheduleMetadata() {
        var json = new ObjectMapper();
        var ai = mock(OpenAiClient.class);
        var planner = new MedicationLivePlanner(ai, json, "fixture-model");
        when(ai.createJson(anyString(), anyList(), anyInt(), anyString(), anyMap())).thenAnswer(call -> {
            assertThat(call.<String>getArgument(0)).contains("DELETE", "STOP");
            var messages = call.<List<org.semiproject.pillbadeumserver.common.ai.AiMessage>>getArgument(1);
            var input = json.readTree(messages.getFirst().content());
            assertThat(input.path("candidates").path(0).path("hasSchedule").asBoolean()).isTrue();
            assertThat(input.path("candidates").path(0).path("startDate").asString()).isEqualTo("2026-11-01");
            var schema = json.valueToTree(call.<Map<String, Object>>getArgument(4));
            assertThat(schema.path("properties").path("intent").path("enum").toString()).contains("DELETE", "STOP");
            assertThat(schema.path("required").toString()).contains("profileHint", "drugHints", "actionEvidence");
            return json.valueToTree(Map.of("intent", "DELETE", "profileHint", "엄마", "drugHints", List.of("타일레놀"),
                    "actionEvidence", "삭제해줘", "useDefaults", false));
        });
        var target = new MedicationTarget(27, 14, "엄마", "PARENT", "타이레놀정500밀리그램", 1, null,
                LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 7), "07:00", LocalDateTime.of(2026, 11, 1, 0, 0));
        var plan = planner.plan(new StartRequest("offer", 14L, 27L, LocalDate.of(2026, 11, 3)),
                LocalDate.of(2026, 11, 3), List.of(target), List.of(), List.of(Map.of("role", "user", "content", "엄마 타일레놀 삭제해줘")));
        assertThat(plan.profileHint()).isEqualTo("엄마");
        assertThat(plan.drugHints()).containsExactly("타일레놀");
        assertThat(plan.actionEvidence()).isEqualTo("삭제해줘");
    }
}
