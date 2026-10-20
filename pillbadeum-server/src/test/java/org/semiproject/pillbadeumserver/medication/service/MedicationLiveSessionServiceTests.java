package org.semiproject.pillbadeumserver.medication.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.AiUnavailableException;
import org.semiproject.pillbadeumserver.common.exception.ExternalApiException;
import org.semiproject.pillbadeumserver.common.exception.NotFoundException;
import org.semiproject.pillbadeumserver.medication.domain.MedicationRepository;
import org.semiproject.pillbadeumserver.medication.domain.MedicationTarget;
import org.semiproject.pillbadeumserver.medication.dto.MedicationDtos.*;
import org.semiproject.pillbadeumserver.medication.dto.MedicationLiveDtos.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MedicationLiveSessionServiceTests {
    @Mock MedicationLiveClient client;
    @Mock MedicationRepository reads;
    @Mock MedicationLiveWorkflow workflow;
    @Mock MedicationLiveClient.Connection connection;
    MedicationLiveSessionService service;
    Consumer<JsonNode> events;
    private final ObjectMapper json = new ObjectMapper();
    private final MutableClock clock = new MutableClock();
    private static final LocalDate DATE = LocalDate.of(2026, 11, 3);
    private final StartRequest request = new StartRequest("offer", 13L, 25L, DATE);

    @BeforeEach
    void setUp() {
        service = new MedicationLiveSessionService(client, reads, workflow, Runnable::run, clock, 300, 10);
    }

    @Test
    void bindsSessionToTokenOwnerAndRejectsForeignAccessAndDuplicateStarts() {
        String id = start();
        assertThat(service.get(5, id).status()).isEqualTo("LISTENING");
        assertThatThrownBy(() -> service.get(99, id)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.stop(99, id)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.start(5, request)).isInstanceOf(BusinessException.class);
        verify(client, times(1)).create(eq("offer"), anyString());
        verify(client, never()).hangup(anyString());
    }

    @Test
    void optionalMedicationStillRequiresAnOwnedProfileBeforePaidSessionCreation() {
        var noDrug = new StartRequest("offer", 17L, null, DATE);
        assertThatThrownBy(() -> service.start(99, noDrug)).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(client, workflow);
        when(reads.ownsProfile(5, 17)).thenReturn(true);
        when(client.create(eq("offer"), anyString())).thenReturn(new MedicationLiveClient.RemoteSession("remote", "answer"));
        when(client.attach(anyString(), any(), any())).thenReturn(connection);
        var started = service.start(5, noDrug);
        assertThat(service.get(5, started.sessionId()).status()).isEqualTo("LISTENING");
        verify(reads, never()).find(anyLong(), anyLong(), anyLong());
    }

    @Test
    void foreignMedicationIsRejectedBeforeCreatingAPaidSession() {
        assertThatThrownBy(() -> service.start(99, request)).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(client, workflow);
    }

    @Test
    void missingFieldsReturnAQuestionAndNeverSave() {
        String id = start();
        var question = new Outcome("몇 시에 등록할까요?", List.of("doseTimes"), List.of(), null);
        when(workflow.prepare(eq(5L), any(), any(), anyList())).thenReturn(new MedicationLiveWorkflow.Decision(0, 0, null, question));
        input("in1", "일주일 약 일정 추가해줘"); delegate("d1");
        assertThat(service.get(5, id).missingFields()).containsExactly("doseTimes");
        verify(workflow, never()).execute(anyLong(), any());
        verify(connection).send(argThat(event -> event.get("type").equals("session.commentary.append")
                && event.get("delegation_id").equals("d1") && event.get("content").equals("몇 시에 등록할까요?")));
    }

    @Test
    void repeatedDelegationsAndDuplicateTranscriptEventsCannotSaveTwice() {
        String id = start();
        ready();
        input("in1", "일주일 동안 8시에 한 개씩 등록해");
        input("in1", "일주일 동안 8시에 한 개씩 등록해");
        delegate("d1"); delegate("d1"); delegate("d2");
        assertThat(service.get(5, id).status()).isEqualTo("SAVED");
        verify(workflow, times(1)).execute(eq(5L), any());
        verify(workflow, times(1)).prepare(eq(5L), any(), any(), anyList());
    }

    @Test
    void savedSessionRejectsFurtherWorkAndClosesAfterTheCompletionGracePeriod() {
        String id = start(); ready();
        input("in1", "알아서 저장해"); delegate("d1");
        verify(connection).send(argThat(event -> event.get("type").equals("session.input_audio.mute")));
        input("in2", "또 다른 일정을 등록해"); delegate("d2");
        verify(workflow, times(1)).prepare(eq(5L), any(), any(), anyList());
        verify(workflow, times(1)).execute(eq(5L), any());
        clock.now = clock.now.plusSeconds(7);
        service.expireSessions();
        assertThat(service.get(5, id).savedSchedule()).isNotNull();
        verify(client, never()).hangup(anyString());
        clock.now = clock.now.plusSeconds(1);
        service.expireSessions();
        verify(client).hangup("live_fixture"); verify(connection).close();
        assertThatThrownBy(() -> service.get(5, id)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void deletionCompletesAndAutoClosesEvenWithoutASavedScheduleResponse() {
        String id = start();
        var decision = new MedicationLiveWorkflow.Decision(14, 27, null,
                new Outcome("처리 중", List.of(), List.of(), null), null, Operation.DELETE);
        when(workflow.prepare(eq(5L), any(), any(), anyList())).thenReturn(decision);
        when(workflow.execute(5, decision)).thenReturn(new Outcome("일정을 삭제했어요.", List.of(), List.of(), null,
                new CompletedOperation(Operation.DELETE, 14, 27)));
        input("in1", "엄마 타일레놀 일정 삭제해줘"); delegate("d1"); delegate("d2");
        var progress = service.get(5, id);
        assertThat(progress.status()).isEqualTo("DELETED");
        assertThat(progress.savedSchedule()).isNull();
        assertThat(progress.completedOperation().myDrugId()).isEqualTo(27);
        verify(workflow, times(1)).execute(5, decision);
        clock.now = clock.now.plusSeconds(8); service.expireSessions();
        verify(client).hangup("live_fixture");
    }

    @Test
    void correctionArrivingDuringDeletionPlanningPreventsTheOldDeletion() {
        String id = start();
        when(workflow.prepare(eq(5L), any(), any(), anyList())).thenAnswer(call -> {
            input("in2", "삭제하지 말고 오늘까지만 먹는 걸로 해줘");
            return new MedicationLiveWorkflow.Decision(14, 27, null,
                    new Outcome("처리 중", List.of(), List.of(), null), null, Operation.DELETE);
        });
        input("in1", "엄마 타이레놀 삭제해줘"); delegate("d1");
        verify(workflow, never()).execute(anyLong(), any());
        assertThat(service.get(5, id).completedOperation()).isNull();
    }

    @Test
    void correctionDuringPlanningDiscardsTheOldDecision() {
        String id = start();
        when(workflow.prepare(eq(5L), any(), any(), anyList())).thenAnswer(call -> {
            input("in2", "아니 저녁 7시로 해줘");
            return decision();
        });
        input("in1", "일주일 동안 8시에 한 개씩 등록해"); delegate("d1");
        verify(workflow, never()).execute(anyLong(), any());
        assertThat(service.get(5, id).status()).isEqualTo("LISTENING");
        verify(connection).send(argThat(event -> event.get("type").equals("session.instructions.append")));
    }

    @Test
    void endingTheConversationDuringPlanningPreventsLateSave() {
        String id = start();
        when(workflow.prepare(eq(5L), any(), any(), anyList())).thenAnswer(call -> {
            service.stop(5, id);
            return decision();
        });
        input("in1", "알아서 저장해"); delegate("d1");
        verify(workflow, never()).execute(anyLong(), any());
        verify(client).hangup("live_fixture");
        assertThatThrownBy(() -> service.get(5, id)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void yesSavesTheStoredProposalWithoutAnotherModelCallAndDoesNotSaveTwice() {
        String id = start();
        var proposal = proposedAt(LocalTime.of(7, 0), LocalTime.of(11, 0), LocalTime.of(17, 0));
        when(workflow.prepare(eq(5L), any(), any(), anyList())).thenReturn(proposal);
        when(workflow.confirm(any())).thenCallRealMethod();
        when(workflow.execute(eq(5L), any())).thenReturn(savedOutcome());
        input("in1", "오늘부터 이틀 테스트약"); delegate("d1");
        assertThat(service.get(5, id).status()).isEqualTo("AWAITING_CONFIRMATION");
        verify(workflow, never()).execute(anyLong(), any());
        events.accept(json.valueToTree(Map.of("type", "session.output_transcript.delta", "event_id", "out1",
                "delta", "기본 하루 세 번으로 적용할까요?", "start_ms", 1000, "end_ms", 2000)));
        input("in2", "응"); delegate("d2"); delegate("d3");
        assertThat(service.get(5, id).status()).isEqualTo("SAVED");
        verify(workflow, times(1)).prepare(eq(5L), any(), any(), anyList());
        verify(workflow).confirm(proposal.proposal());
        verify(workflow, times(1)).execute(eq(5L), argThat(decision -> decision.request().equals(proposal.proposal().request())));
    }

    @Test
    void correctionReplacesTheProposalAndOnlyTheNewProposalIsConfirmed() {
        String id = start();
        var first = proposedAt(LocalTime.of(7, 0), LocalTime.of(11, 0), LocalTime.of(17, 0));
        var evening = proposedAt(LocalTime.of(17, 0));
        when(workflow.prepare(eq(5L), any(), any(), anyList())).thenReturn(first, evening);
        when(workflow.confirm(any())).thenCallRealMethod();
        when(workflow.execute(eq(5L), any())).thenReturn(savedOutcome());
        input("in1", "오늘부터 이틀 테스트약"); delegate("d1");
        input("in2", "응 아니 저녁에만"); delegate("d2");
        verify(workflow, never()).execute(anyLong(), any());
        verify(workflow, never()).confirm(any());
        input("in3", "네"); delegate("d3");
        verify(workflow, times(2)).prepare(eq(5L), any(), any(), anyList());
        verify(workflow).confirm(evening.proposal());
        verify(workflow).execute(eq(5L), argThat(decision -> decision.request().doseTimes().equals(List.of(LocalTime.of(17, 0)))));
        assertThat(service.get(5, id).status()).isEqualTo("SAVED");
    }

    @Test
    void confirmingAfterSessionExpiryCannotSaveTheStoredProposal() {
        String id = start();
        when(workflow.prepare(eq(5L), any(), any(), anyList())).thenReturn(proposedAt(LocalTime.of(17, 0)));
        when(workflow.confirm(any())).thenCallRealMethod();
        input("in1", "오늘부터 이틀 테스트약"); delegate("d1");
        clock.now = clock.now.plusSeconds(60);
        input("in2", "응"); delegate("d2");
        verify(workflow, never()).execute(anyLong(), any());
        verify(client).hangup("live_fixture");
        assertThatThrownBy(() -> service.get(5, id)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void yesWithoutAStoredProposalDoesNotCallTheModelOrSave() {
        String id = start();
        input("in1", "응"); delegate("d1");
        assertThat(service.get(5, id).status()).isEqualTo("WAITING_FOR_DETAILS");
        verify(workflow, never()).prepare(anyLong(), any(), any(), anyList());
        verify(workflow, never()).confirm(any());
        verify(workflow, never()).execute(anyLong(), any());
    }

    @Test
    void cancellationDiscardsTheProposalSoLaterYesCannotSaveIt() {
        String id = start();
        when(workflow.prepare(eq(5L), any(), any(), anyList())).thenReturn(proposedAt(LocalTime.of(17, 0)),
                new MedicationLiveWorkflow.Decision(0, 0, null, new Outcome("취소했어요", List.of(), List.of(), null)));
        input("in1", "오늘부터 이틀 테스트약"); delegate("d1");
        input("in2", "등록 취소해"); delegate("d2");
        input("in3", "응"); delegate("d3");
        verify(workflow, times(2)).prepare(eq(5L), any(), any(), anyList());
        verify(workflow, never()).confirm(any());
        verify(workflow, never()).execute(anyLong(), any());
        assertThat(service.get(5, id).status()).isEqualTo("WAITING_FOR_DETAILS");
    }

    @Test
    void expiredSessionStopsBeforeCommittingAnAlreadyPreparedDecision() {
        start();
        when(workflow.prepare(eq(5L), any(), any(), anyList())).thenAnswer(call -> {
            clock.now = clock.now.plusSeconds(60);
            return decision();
        });
        input("in1", "알아서 저장해"); delegate("d1");
        verify(workflow, never()).execute(anyLong(), any());
        verify(client).hangup("live_fixture");
    }

    @Test
    void saveFailureIsNotAutomaticallyRetriedByAnotherDelegation() {
        start();
        when(workflow.prepare(eq(5L), any(), any(), anyList())).thenReturn(decision());
        when(workflow.execute(eq(5L), any())).thenThrow(new NotFoundException("약이 삭제됐습니다."));
        input("in1", "알아서 저장해"); delegate("d1"); delegate("d2");
        verify(workflow, times(1)).execute(eq(5L), any());
    }

    @Test
    void sessionsAreCappedAtOneMinuteAndReleaseTheRemoteConnectionAtTheDeadline() {
        String id = start();
        clock.now = clock.now.plusSeconds(59);
        service.expireSessions();
        assertThat(service.get(5, id).status()).isEqualTo("LISTENING");
        verify(client, never()).hangup(anyString());
        clock.now = clock.now.plusSeconds(1);
        service.expireSessions();
        verify(client).hangup("live_fixture"); verify(connection).close();
        assertThatThrownBy(() -> service.get(5, id)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void attachFailureClosesRemoteSessionAndReleasesTheMemberSlot() {
        setupStart();
        doThrow(ExternalApiException.failed("OpenAI Live", "fixture", null)).when(client).attach(anyString(), any(), any());
        assertThatThrownBy(() -> service.start(5, request)).isInstanceOf(ExternalApiException.class);
        verify(client).hangup("live_fixture");
        doAnswer(call -> { events = call.getArgument(1); return connection; }).when(client).attach(anyString(), any(), any());
        assertThat(service.start(5, request).sessionId()).isNotBlank();
    }

    @Test
    void sessionCreatedAfterExpiryIsHungUpWithoutAttaching() {
        setupSelection();
        doAnswer(call -> {
            clock.now = clock.now.plusSeconds(60);
            service.expireSessions();
            return new MedicationLiveClient.RemoteSession("live_late", "answer");
        }).when(client).create(anyString(), anyString());
        assertThatThrownBy(() -> service.start(5, request)).isInstanceOf(ExternalApiException.class);
        verify(client).hangup("live_late");
        verify(client, never()).attach(anyString(), any(), any());
    }

    @Test
    void missingBackendConfigurationIsRejectedBeforeCreatingAPaidSession() {
        setupSelection();
        doThrow(new AiUnavailableException("해석 모델이 없습니다.")).when(workflow).requireReady();
        assertThatThrownBy(() -> service.start(5, request)).isInstanceOf(AiUnavailableException.class);
        verifyNoInteractions(client);
    }

    private void ready() {
        when(workflow.prepare(eq(5L), any(), any(), anyList())).thenReturn(decision());
        var saved = new ScheduleResponse(25, 13, "엄마", "테스트약", DATE, DATE.plusDays(6), List.of("08:00"), 1, null, false, true);
        when(workflow.execute(eq(5L), any())).thenReturn(new Outcome("저장했어요", List.of(), List.of(), saved));
    }
    private MedicationLiveWorkflow.Decision decision() {
        return new MedicationLiveWorkflow.Decision(13, 25,
                new ScheduleRequest(DATE, DATE.plusDays(6), List.of(LocalTime.of(8, 0)), 1, null),
                new Outcome("준비됨", List.of(), List.of(), null));
    }
    private MedicationLiveWorkflow.Decision proposedAt(LocalTime... times) {
        var request = new ScheduleRequest(DATE, DATE.plusDays(1), List.of(times), 2, null);
        return new MedicationLiveWorkflow.Decision(0, 0, null,
                new Outcome("기본 설정으로 적용해 저장할까요?", List.of("doseTimes", "dosePerTime"), List.of(), null),
                new MedicationLiveWorkflow.Proposal(13, 25, request, List.of("doseTimes", "dosePerTime")));
    }
    private Outcome savedOutcome() {
        var saved = new ScheduleResponse(25, 13, "엄마", "테스트약", DATE, DATE.plusDays(1), List.of("17:00"), 2, null, false, true);
        return new Outcome("등록했어요", List.of(), List.of("doseTimes", "dosePerTime"), saved);
    }
    private String start() {
        setupStart();
        when(client.attach(anyString(), any(), any())).thenAnswer(call -> { events = call.getArgument(1); return connection; });
        return service.start(5, request).sessionId();
    }
    private void setupStart() {
        setupSelection();
        when(client.create(anyString(), anyString())).thenReturn(new MedicationLiveClient.RemoteSession("live_fixture", "answer"));
    }
    private void setupSelection() {
        when(reads.find(5, 13, 25)).thenReturn(Optional.of(new MedicationTarget(25, 13, "엄마", "MOTHER", "테스트약", 1, null,
                null, null, null, LocalDateTime.of(2026, 10, 19, 0, 0))));
    }
    private void input(String id, String text) {
        events.accept(json.valueToTree(Map.of("type", "session.input_transcript.delta", "event_id", id, "delta", text, "start_ms", 0, "end_ms", 1000)));
    }
    private void delegate(String id) {
        events.accept(json.valueToTree(Map.of("type", "session.delegation.created", "event_id", "evt-" + id,
                "delegation", Map.of("id", id, "target", "client"))));
    }
    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-19T00:00:00Z");
        public ZoneId getZone() { return ZoneId.of("Asia/Seoul"); }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
