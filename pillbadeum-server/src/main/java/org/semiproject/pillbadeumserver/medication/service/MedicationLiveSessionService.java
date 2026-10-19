package org.semiproject.pillbadeumserver.medication.service;

import jakarta.annotation.PreDestroy;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.common.exception.ExternalApiException;
import org.semiproject.pillbadeumserver.common.exception.NotFoundException;
import org.semiproject.pillbadeumserver.medication.domain.MedicationRepository;
import org.semiproject.pillbadeumserver.medication.dto.MedicationDtos.ScheduleResponse;
import org.semiproject.pillbadeumserver.medication.dto.MedicationLiveDtos.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/** 단일 서버용 짧은 Live 세션. 대화·초안은 메모리에만 있고 일정만 기존 DB에 저장한다. */
@Service
public class MedicationLiveSessionService {
    private static final Logger log = LoggerFactory.getLogger(MedicationLiveSessionService.class);
    private final MedicationLiveClient client;
    private final MedicationRepository reads;
    private final MedicationLiveWorkflow workflow;
    private final Executor executor;
    private final Clock clock;
    private final long durationSeconds;
    private final int maxSessions;
    private final Map<String, State> sessions = new ConcurrentHashMap<>();
    private final Map<Long, String> owners = new ConcurrentHashMap<>();
    private final Object registryLock = new Object();
    private static final String INSTRUCTIONS = """
            한국어로 복약 일정을 등록하는 음성 도우미다. 짧게 한 번에 한 가지씩 질문한다.
            시작 인사부터 기본 말하기 속도는 평소의 약 1.5배로 빠르고 자연스럽게 유지한다.
            문장 사이의 불필요한 쉼을 줄이되 날짜·시간·복용 개수는 명확하게 발음한다.
            사용자가 말하기 속도를 바꿔 달라고 하면 그 요청을 따른다.
            AI가 생성하는 음성이라고 대화 시작 때 짧게 알린다. 진료·복용법을 추천하지 않는다.
            사용자가 말을 시작한 뒤 약 1초 동안 말을 멈추면 현재 발화가 끝난 것으로 보고 더 기다리지 않는다.
            문장이 덜 끝난 것 같아도 그때까지 들은 내용으로 처리하고, 부족한 정보는 다음 질문으로 확인한다.
            아직 사용자가 아무 말도 하지 않았거나 기침·주변 소리만 들렸을 때는 요청을 만들지 않는다.
            사용자가 다시 말하거나 끼어들면 답변을 멈추고 새로 말한 내용을 듣는다.
            사용자에게서 약 일정 등록·정정·기본값 위임·취소 요청 또는 추가 답변을 받으면,
            위 발화 종료 기준에 도달하는 즉시 backend에 위임한다. backend가 항목 수집과 저장을 담당한다.
            부족한 정보를 임의로 채우지 않는다. backend의 질문이 오면 그 질문을 한다.
            '알아서 저장해'처럼 맡기면 backend에 그 요청을 전달하고, 그 전까지 기본값으로 저장하지 않는다.
            사용자가 계속 말하는 도중에는 문장 조각만으로 작업을 요청하지 않는다. 사용자의 정정을 반영한다.
            backend가 저장 성공을 알려주기 전에는 저장했다고 말하지 않는다. 성공 결과가 와야 완료를 알린다.
            대화가 끊겼다고 이미 저장한 일정이 취소됐다고 말하지 않는다. 수정·삭제는 실제 결과로만 안내한다.
            저장 외의 도구는 지원하지 않는다. 실제 일정 삭제·그만 먹기는 화면의 기존 기능을 안내한다.
            """;

    @Autowired
    public MedicationLiveSessionService(MedicationLiveClient client, MedicationRepository reads, MedicationLiveWorkflow workflow,
                                         @Qualifier("applicationTaskExecutor") Executor executor,
                                         @Value("${OPENAI_LIVE_MAX_SECONDS:300}") long durationSeconds,
                                         @Value("${OPENAI_LIVE_MAX_SESSIONS:10}") int maxSessions) {
        this(client, reads, workflow, executor, Clock.system(ZoneId.of("Asia/Seoul")), durationSeconds, maxSessions);
    }

    MedicationLiveSessionService(MedicationLiveClient client, MedicationRepository reads, MedicationLiveWorkflow workflow,
                                  Executor executor, Clock clock, long durationSeconds, int maxSessions) {
        if (durationSeconds < 1 || maxSessions < 1) throw new IllegalArgumentException("Live 연결 제한은 양수여야 합니다.");
        this.client = client; this.reads = reads; this.workflow = workflow; this.executor = executor;
        this.clock = clock; this.durationSeconds = durationSeconds; this.maxSessions = maxSessions;
    }

    public StartResponse start(long memberId, StartRequest request) {
        reads.find(memberId, request.selectedProfileId(), request.selectedMyDrugId())
                .orElseThrow(() -> new NotFoundException("조회할 수 있는 내 약이 없습니다."));
        workflow.requireReady();
        State state = new State(memberId, request, Instant.now(clock).plusSeconds(durationSeconds));
        synchronized (registryLock) {
            if (owners.containsKey(memberId)) throw new BusinessException(ErrorCode.DUPLICATE, "진행 중인 음성 대화를 먼저 종료해주세요.");
            if (sessions.size() >= maxSessions) throw ExternalApiException.failed("OpenAI Live", "대화 연결이 많습니다.", null);
            owners.put(memberId, state.id); sessions.put(state.id, state);
        }
        try {
            // 사용자 지정 이름은 지시문에 섞지 않는다. 자세한 후보·선택 정보는 backend가 조회한다.
            String context = "\n화면에서 선택한 날짜는 " + request.selectedDate() + "이다. 선택한 프로필과 약은 backend가 알고 있다.";
            var remote = client.create(request.sdp(), INSTRUCTIONS + context);
            boolean closedWhileCreating;
            synchronized (state) {
                closedWhileCreating = state.closed;
                if (!closedWhileCreating) state.remoteId = remote.id();
            }
            if (closedWhileCreating) {
                // 원격 생성 응답 전에 만료·서버 종료가 먼저 일어났어도 뒤늦은 유료 세션을 남기지 않는다.
                try { client.hangup(remote.id()); }
                catch (RuntimeException e) { log.info("[늦은 Live 세션 종료 확인 실패] type={}", e.getClass().getSimpleName()); }
                throw ExternalApiException.failed("OpenAI Live", "연결 준비 시간이 종료됐습니다.", null);
            }
            var connection = client.attach(remote.id(), event -> receive(state, event), () -> disconnected(state));
            boolean run;
            synchronized (state) {
                state.connection = connection;
                if (state.closed) {
                    connection.close();
                    throw ExternalApiException.failed("OpenAI Live", "연결이 종료됐습니다.", null);
                }
                state.status = "LISTENING";
                run = state.pending != null && !state.running;
                if (run) state.running = true;
            }
            if (run) executor.execute(() -> process(state));
            return new StartResponse(state.id, remote.sdp(), state.expiresAt);
        } catch (RuntimeException e) {
            terminate(state, true);
            throw e;
        }
    }

    public Progress get(long memberId, String sessionId) {
        State state = owned(memberId, sessionId);
        synchronized (state) {
            return new Progress(state.id, state.status, state.message, state.missing, state.defaults, state.saved, state.expiresAt);
        }
    }

    public void stop(long memberId, String sessionId) { terminate(owned(memberId, sessionId), true); }

    private State owned(long memberId, String id) {
        State state = sessions.get(id);
        if (state == null || state.memberId != memberId) throw new NotFoundException("조회할 수 있는 음성 대화가 없습니다.");
        if (!Instant.now(clock).isBefore(state.expiresAt)) {
            terminate(state, true);
            throw new NotFoundException("음성 대화 시간이 종료됐습니다.");
        }
        return state;
    }

    private void receive(State state, JsonNode event) {
        String type = event.path("type").asString("");
        if ("session.closed".equals(type)) { terminate(state, false); return; }
        if ("error".equals(type)) { executor.execute(() -> terminate(state, true)); return; }
        if (!List.of("session.input_transcript.delta", "session.output_transcript.delta", "session.delegation.created").contains(type)) return;
        boolean run = false;
        synchronized (state) {
            if (state.closed) return;
            String eventId = event.path("event_id").asString("");
            if (!eventId.isBlank() && !state.events.add(eventId)) return;
            if (state.events.size() > 2000) { executor.execute(() -> terminate(state, true)); return; }
            if (type.endsWith("transcript.delta")) {
                String delta = event.path("delta").asString("");
                if (delta.isEmpty()) return;
                state.characters += delta.length();
                if (state.characters > 16000) { executor.execute(() -> terminate(state, true)); return; }
                boolean user = type.equals("session.input_transcript.delta");
                state.transcript.add(Map.of("role", user ? "user" : "assistant", "content", delta,
                        "start_ms", event.path("start_ms").asLong(), "end_ms", event.path("end_ms").asLong()));
                if (user) state.revision++;
            } else if ("client".equals(event.path("delegation").path("target").asString())) {
                String id = event.path("delegation").path("id").asString("");
                if (id.isBlank() || !state.delegations.add(id)) return;
                state.pending = id;
                if (!state.running && state.connection != null) { state.running = true; run = true; }
            }
        }
        if (run) executor.execute(() -> process(state));
    }

    private void process(State state) {
        while (true) {
            String delegation;
            long revision;
            List<Map<String, Object>> transcript;
            synchronized (state) {
                if (state.closed || state.pending == null) { state.running = false; return; }
                delegation = state.pending; state.pending = null;
                revision = state.revision;
                transcript = state.transcript.stream().sorted(Comparator
                        .comparingLong((Map<String, Object> part) -> ((Number) part.get("start_ms")).longValue())
                        .thenComparingLong(part -> ((Number) part.get("end_ms")).longValue())).toList();
                state.status = "PROCESSING";
            }
            try {
                Outcome outcome;
                synchronized (state) {
                    outcome = revision == state.processedRevision ? state.lastOutcome : null;
                }
                if (revision == 0) outcome = new Outcome("등록할 약 일정을 말씀해주세요.", List.of("request"), List.of(), null);
                if (outcome == null) {
                    var decision = workflow.prepare(state.memberId, state.selection, LocalDate.now(clock), transcript);
                    synchronized (state) {
                        if (state.closed) return;
                        if (!Instant.now(clock).isBefore(state.expiresAt)) { terminate(state, true); return; }
                        if (state.revision != revision) {
                            state.status = "LISTENING";
                            state.message = "추가로 말씀하신 내용까지 듣고 다시 확인할게요.";
                            send(state, delegation, "session.instructions.append", "사용자의 추가 발화가 도착해 이전 저장은 실행하지 않았다. 사용자가 말을 멈춘 뒤 약 1초가 지나면 추가 발화까지 포함해 다시 backend에 위임하라.");
                            continue;
                        }
                        // 이 세션의 종료·중복 실행과 저장을 직렬화한다. 저장 서비스에서 소유권도 재검사한다.
                        outcome = decision.request() == null ? decision.outcome() : workflow.save(state.memberId, decision);
                        state.processedRevision = revision;
                        state.lastOutcome = outcome;
                    }
                }
                synchronized (state) {
                    if (state.closed) return;
                    state.message = outcome.message(); state.missing = outcome.missingFields(); state.defaults = outcome.appliedDefaults();
                    if (outcome.savedSchedule() != null) state.saved = outcome.savedSchedule();
                    state.status = outcome.savedSchedule() == null ? "WAITING_FOR_DETAILS" : "SAVED";
                }
                send(state, delegation, "session.commentary.append", outcome.message());
            } catch (RuntimeException e) {
                synchronized (state) {
                    if (state.closed) return;
                    state.status = "WAITING_FOR_DETAILS";
                    state.message = e instanceof BusinessException ? e.getMessage() : "일정 처리 결과를 확인하지 못했습니다. 캘린더를 확인하고 다시 말씀해주세요.";
                    state.processedRevision = revision;
                    state.lastOutcome = new Outcome(state.message, List.of(), List.of(), null);
                }
                log.info("[Live 일정 처리 실패] type={}", e.getClass().getSimpleName());
                try { send(state, delegation, "session.commentary.append", state.message); }
                catch (RuntimeException ignored) { terminate(state, true); return; }
            }
        }
    }

    private void send(State state, String delegation, String type, String content) {
        var connection = state.connection;
        if (connection != null && !state.closed) connection.send(Map.of("type", type, "event_id", UUID.randomUUID().toString(),
                "delegation_id", delegation, "content", content));
    }

    private void disconnected(State state) { executor.execute(() -> terminate(state, true)); }

    private void terminate(State state, boolean hangup) {
        synchronized (state) {
            if (state.closed) return;
            state.closed = true; state.status = "CLOSED"; state.transcript.clear();
        }
        sessions.remove(state.id, state); owners.remove(state.memberId, state.id);
        if (hangup && state.remoteId != null) {
            try { client.hangup(state.remoteId); }
            catch (RuntimeException e) { log.info("[Live 강제 종료 확인 실패] type={}", e.getClass().getSimpleName()); }
        }
        if (state.connection != null) state.connection.close();
    }

    @Scheduled(fixedDelay = 10000)
    void expireSessions() {
        for (State state : sessions.values()) if (!Instant.now(clock).isBefore(state.expiresAt)) executor.execute(() -> terminate(state, true));
    }

    @PreDestroy
    void shutdown() { for (State state : List.copyOf(sessions.values())) terminate(state, true); }

    private static final class State {
        final String id = UUID.randomUUID().toString();
        final long memberId;
        final StartRequest selection;
        final Instant expiresAt;
        final List<Map<String, Object>> transcript = new ArrayList<>();
        final HashSet<String> events = new HashSet<>(), delegations = new HashSet<>();
        volatile String remoteId;
        volatile MedicationLiveClient.Connection connection;
        volatile boolean closed;
        String status = "CONNECTING", message = "음성 대화를 시작할게요.", pending;
        long revision, processedRevision = -1;
        int characters;
        boolean running;
        List<String> missing = List.of(), defaults = List.of();
        ScheduleResponse saved;
        Outcome lastOutcome;
        State(long memberId, StartRequest selection, Instant expiresAt) {
            this.memberId = memberId;
            this.selection = new StartRequest("", selection.selectedProfileId(), selection.selectedMyDrugId(), selection.selectedDate());
            this.expiresAt = expiresAt;
        }
    }
}
