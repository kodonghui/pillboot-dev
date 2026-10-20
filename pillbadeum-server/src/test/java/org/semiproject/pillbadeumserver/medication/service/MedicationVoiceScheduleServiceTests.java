package org.semiproject.pillbadeumserver.medication.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ExternalApiException;
import org.semiproject.pillbadeumserver.common.exception.NotFoundException;
import org.semiproject.pillbadeumserver.medication.domain.MedicationRepository;
import org.semiproject.pillbadeumserver.medication.domain.MedicationTarget;
import org.semiproject.pillbadeumserver.medication.dto.MedicationDtos.ScheduleRequest;
import org.semiproject.pillbadeumserver.medication.dto.MedicationDtos.ScheduleResponse;
import org.semiproject.pillbadeumserver.medication.dto.MedicationVoiceDtos.*;

import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MedicationVoiceScheduleServiceTests {
    @Mock MedicationRepository reads;
    @Mock MedicationVoiceInterpreter interpreter;
    @Mock MedicationScheduleService schedules;
    @Mock MedicationSpeechClient speech;
    MedicationVoiceScheduleService service;
    private static final LocalDate SELECTED = LocalDate.of(2026, 11, 3);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 19);
    private final CommandRequest request = new CommandRequest("일주일 동안 약 일정 추가해줘", 13L, 25L, SELECTED);
    private final List<MedicationTarget> choices = List.of(target(25, 13), target(27, 14));

    @BeforeEach
    void setUp() {
        service = new MedicationVoiceScheduleService(reads, interpreter, schedules, speech,
                Clock.fixed(Instant.parse("2026-10-18T15:30:00Z"), ZoneId.of("Asia/Seoul")));
    }

    @Test
    void appliesSelectedDateDrugAndScreenTimesWithoutAConfirmationStep() {
        parsed(new ParsedCommand("CREATE_SCHEDULE", null, null, null, 7, null, null, null, null));
        when(schedules.save(eq(5L), eq(13L), eq(25L), any())).thenAnswer(call -> response(call.getArgument(3)));
        when(speech.speak(anyString())).thenReturn(new byte[]{1, 2, 3});

        var result = service.execute(5, request);

        assertThat(result.schedule().startDate()).isEqualTo(SELECTED);
        assertThat(result.schedule().endDate()).isEqualTo(SELECTED.plusDays(6));
        assertThat(result.schedule().doseTimes()).containsExactly("07:00", "11:00", "17:00");
        assertThat(result.schedule().dosePerTime()).isEqualTo(2);
        assertThat(result.schedule().timing()).isEqualTo("식후");
        assertThat(result.appliedDefaults()).contains("myDrugId", "startDate", "doseTimes", "dosePerTime", "timing");
        assertThat(result.audioStatus()).isEqualTo(AudioStatus.READY);
        assertThat(result.audioBase64()).isEqualTo("AQID");
        assertThat(result.aiGeneratedVoice()).isTrue();
        verify(interpreter).parse(request, TODAY, choices);
        var order = inOrder(schedules, speech);
        order.verify(schedules).save(eq(5L), eq(13L), eq(25L), any());
        order.verify(speech).speak("프로필13님의 약 일정을 저장했습니다.");
    }

    @Test
    void omittedPeriodUsesTheClickedDayOnly() {
        parsed(new ParsedCommand("CREATE_SCHEDULE", null, null, null, null, null, null, null, null));
        when(schedules.save(eq(5L), eq(13L), eq(25L), any())).thenAnswer(call -> response(call.getArgument(3)));
        when(speech.speak(anyString())).thenReturn(new byte[]{1});
        var result = service.execute(5, request);
        assertThat(result.schedule().startDate()).isEqualTo(SELECTED);
        assertThat(result.schedule().endDate()).isEqualTo(SELECTED);
        assertThat(result.appliedDefaults()).contains("endDate");
    }

    @Test
    void explicitTargetAndDatesOverrideCurrentSelection() {
        parsed(new ParsedCommand("CREATE_SCHEDULE", 27L, "2026-10-19", "2026-10-25", null,
                List.of("08:00", "19:00"), 2, 1, "식전"));
        when(schedules.save(eq(5L), eq(14L), eq(27L), any())).thenReturn(new ScheduleResponse(
                27, 14, "엄마", "테스트약", TODAY, TODAY.plusDays(6), List.of("08:00", "19:00"), 1, "식전", false, true));
        when(speech.speak(anyString())).thenReturn(new byte[]{1});
        var result = service.execute(5, request);
        assertThat(result.appliedDefaults()).isEmpty();
        verify(schedules).save(eq(5L), eq(14L), eq(27L), argThat(body -> body.startDate().equals(TODAY)
                && body.doseTimes().equals(List.of(LocalTime.of(8, 0), LocalTime.of(19, 0)))));
    }

    @Test
    void foreignSelectedDrugIsRejectedBeforePaidAiCalls() {
        when(reads.allDrugs(99)).thenReturn(List.of());
        assertThatThrownBy(() -> service.execute(99, request)).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(interpreter, schedules, speech);
    }

    @Test
    void hallucinatedOrForeignAiTargetCannotBeSaved() {
        parsed(new ParsedCommand("CREATE_SCHEDULE", 999L, null, null, 7, null, null, null, null));
        assertThatThrownBy(() -> service.execute(5, request)).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(schedules, speech);
    }

    @Test
    void unsupportedCommandsAndInvalidScheduleValuesNeverReachSave() {
        when(reads.allDrugs(5)).thenReturn(choices);
        var invalidCommands = List.of(
                new ParsedCommand("UNSUPPORTED", null, null, null, null, null, null, null, null),
                new ParsedCommand("TARGET_NOT_FOUND", null, null, null, null, null, null, null, null),
                new ParsedCommand("CREATE_SCHEDULE", null, null, null, 0, null, null, null, null),
                new ParsedCommand("CREATE_SCHEDULE", null, "2026-02-30", null, 7, null, null, null, null),
                new ParsedCommand("CREATE_SCHEDULE", null, null, null, 7, null, 4, null, null),
                new ParsedCommand("CREATE_SCHEDULE", null, null, null, 7, List.of("07:00", "07:00"), null, null, null),
                new ParsedCommand("CREATE_SCHEDULE", null, null, null, 7, List.of("25:00"), null, null, null),
                new ParsedCommand("CREATE_SCHEDULE", null, null, null, 7, null, null, 11, null),
                new ParsedCommand("CREATE_SCHEDULE", null, "2026-10-25", "2026-10-19", null, null, null, null, null),
                new ParsedCommand("CREATE_SCHEDULE", null, "2026-10-19", "2026-10-25", 2, null, null, null, null));
        for (var command : invalidCommands) {
            when(interpreter.parse(request, TODAY, choices)).thenReturn(command);
            assertThatThrownBy(() -> service.execute(5, request)).isInstanceOf(BusinessException.class);
        }
        verifyNoInteractions(schedules, speech);
    }

    @Test
    void aiFailureDoesNotSave() {
        when(reads.allDrugs(5)).thenReturn(choices);
        when(interpreter.parse(request, TODAY, choices)).thenThrow(ExternalApiException.timeout("OpenAI", null));
        assertThatThrownBy(() -> service.execute(5, request)).isInstanceOf(ExternalApiException.class);
        verifyNoInteractions(schedules, speech);
    }

    @Test
    void speechFailureDoesNotUndoASuccessfulSave() {
        parsed(new ParsedCommand("CREATE_SCHEDULE", null, null, null, 7, null, null, null, null));
        when(schedules.save(eq(5L), eq(13L), eq(25L), any())).thenAnswer(call -> response(call.getArgument(3)));
        when(speech.speak(anyString())).thenThrow(ExternalApiException.timeout("OpenAI TTS", null));
        var result = service.execute(5, request);
        assertThat(result.schedule().myDrugId()).isEqualTo(25);
        assertThat(result.audioStatus()).isEqualTo(AudioStatus.UNAVAILABLE);
        assertThat(result.audioBase64()).isNull();
        assertThat(result.message()).contains("저장했습니다");
        verify(schedules, times(1)).save(eq(5L), eq(13L), eq(25L), any());
    }

    @Test
    void validationFailureDuringSaveNeverAnnouncesSuccess() {
        parsed(new ParsedCommand("CREATE_SCHEDULE", null, null, null, 7, null, null, null, null));
        when(schedules.save(eq(5L), eq(13L), eq(25L), any())).thenThrow(new NotFoundException("이미 삭제된 약입니다."));
        assertThatThrownBy(() -> service.execute(5, request)).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(speech);
    }

    private void parsed(ParsedCommand command) {
        when(reads.allDrugs(5)).thenReturn(choices);
        when(interpreter.parse(request, TODAY, choices)).thenReturn(command);
    }

    private ScheduleResponse response(ScheduleRequest request) {
        return new ScheduleResponse(25, 13, "프로필13", "테스트약", request.startDate(), request.endDate(),
                request.doseTimes().stream().map(time -> time.toString()).toList(), request.dosePerTime(), request.timing(), false, true);
    }

    private MedicationTarget target(long drugId, long profileId) {
        return new MedicationTarget(drugId, profileId, "프로필" + profileId, "FAMILY", "테스트약", 2, "식후",
                null, null, null, LocalDateTime.of(2026, 10, 19, 9, 0));
    }
}
