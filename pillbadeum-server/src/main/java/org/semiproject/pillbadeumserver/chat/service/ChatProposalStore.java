package org.semiproject.pillbadeumserver.chat.service;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.semiproject.pillbadeumserver.chat.dto.ChatProposalResponse;
import org.semiproject.pillbadeumserver.chat.dto.QuestionResponse;
import org.semiproject.pillbadeumserver.common.config.TimeZoneConfig;
import org.semiproject.pillbadeumserver.drug.domain.DrugRegistered;
import org.semiproject.pillbadeumserver.drug.domain.DrugSource;
import org.semiproject.pillbadeumserver.drug.domain.MyDrugRepository;
import org.semiproject.pillbadeumserver.drug.domain.StrengthMatch;
import org.semiproject.pillbadeumserver.image.service.ImageKeywordRegistrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * V3-CHAT-002~005: 제안 표(SQL 16)를 읽고 쓴다. 카드를 보여 주는 약봉이 메시지와 같은 트랜잭션에서 만들고,
 * 읽을 때는 로그인 회원의 제안만 돌려준다(다른 회원 것은 없는 것과 같음, V3-COMMON-003).
 * 상태는 "대기이고 만료 전"인 행만 바꾸는 조건부 UPDATE 한 줄로 바꾼다(SQL 16). 바뀐 행이 0이면 호출자가 409로 답한다.
 */
@Service
public class ChatProposalStore {
    private static final Logger log = LoggerFactory.getLogger(ChatProposalStore.class);
    static final Duration LIFETIME = Duration.ofMinutes(30);
    private static final int EXPIRE_BATCH = 500;
    private static final String SELECT = """
            SELECT p.proposal_id, p.kind, p.status, p.profile_id, p.message_id, p.created_at, p.expires_at, p.decided_at,
                   i.item_no, i.item_seq, i.mention, i.strength_match, i.already_registered, i.chosen, i.my_drug_id,
                   d.name, d.maker, d.image_url, d.base_name, d.strength_text
            FROM chat_proposal p
            JOIN profile f ON f.profile_id = p.profile_id AND f.member_id = p.member_id
            LEFT JOIN chat_proposal_item i ON i.proposal_id = p.proposal_id
            LEFT JOIN drug d ON d.item_seq = i.item_seq
            """;

    private final JdbcTemplate jdbc;
    private final QuestionStore questions;
    private final MyDrugRepository medications;
    private final ImageKeywordRegistrationService drugCache;
    private final ApplicationEventPublisher events;

    public ChatProposalStore(JdbcTemplate jdbc, QuestionStore questions, MyDrugRepository medications,
                             ImageKeywordRegistrationService drugCache, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.questions = questions;
        this.medications = medications;
        this.drugCache = drugCache;
        this.events = events;
    }

    /**
     * 등록 요청 질문의 답변 메시지와 등록 제안을 한 트랜잭션에 저장한다. 후보 약은 사진 후보처럼 약 캐시(drug)에 먼저 넣고,
     * 이미 그 프로필 약목록에 있는 품목은 '이미 등록됨'으로 둔다. 약목록(my_drug)에는 아무것도 저장하지 않는다.
     */
    @Transactional(timeout = 5)
    public QuestionResponse.Answer saveRegisterAnswer(Long sessionId, Long memberId, Long profileId,
                                                      QuestionResponse.Question question, String content,
                                                      List<RegistrationCandidates.Candidate> picked,
                                                      ChatDeadline deadline, boolean retry) {
        var answer = questions.saveNotice(sessionId, memberId, profileId, question, content, deadline, retry);
        for (var candidate : picked) drugCache.cache(candidate.drug(), "PERMIT");
        return answer.withProposal(insertRegister(memberId, profileId, answer.messageId(), picked));
    }

    /**
     * V3-CHAT-006: 상담방 사진 한 장의 회원 턴과 답변(고른 후보가 있으면 등록 제안까지)을 한 트랜잭션에 저장한다.
     * 사진 후보는 판독 때 약 캐시에 들어간 품목이라(ocr_candidate FK) 다시 넣지 않는다. 약목록에는 저장하지 않는다.
     */
    @Transactional(timeout = 5)
    public QuestionResponse savePhotoTurn(Long sessionId, Long memberId, Long profileId, String turn, String content,
                                          List<RegistrationCandidates.Candidate> picked, ChatDeadline deadline) {
        var question = questions.save(sessionId, memberId, profileId, turn);
        var answer = questions.saveNotice(sessionId, memberId, profileId, question, content, deadline, false);
        if (!picked.isEmpty()) answer = answer.withProposal(insertRegister(memberId, profileId, answer.messageId(), picked));
        return QuestionResponse.answered(question, answer);
    }

    private ChatProposalResponse insertRegister(Long memberId, Long profileId, Long messageId,
                                                List<RegistrationCandidates.Candidate> picked) {
        var registered = new HashSet<>(medications.findItemSeqsByProfileId(profileId));
        LocalDateTime now = now();
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("INSERT INTO chat_proposal(member_id, profile_id, "
                    + "message_id, kind, status, created_at, expires_at) VALUES(?,?,?,'REGISTER','PENDING',?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, memberId);
            statement.setLong(2, profileId);
            statement.setLong(3, messageId);
            statement.setObject(4, now);
            statement.setObject(5, now.plus(LIFETIME));
            return statement;
        }, key);
        long proposalId = Objects.requireNonNull(key.getKey()).longValue();
        var rows = new ArrayList<Object[]>();
        for (int i = 0; i < picked.size(); i++) {
            var candidate = picked.get(i);
            rows.add(new Object[]{proposalId, i + 1, candidate.drug().itemSeq(), candidate.mention(),
                    candidate.strength().name(), registered.contains(candidate.drug().itemSeq())});
        }
        jdbc.batchUpdate("INSERT INTO chat_proposal_item(proposal_id, item_no, item_seq, mention, strength_match, "
                + "already_registered) VALUES(?,?,?,?,?,?)", rows);
        return find(proposalId, memberId).orElseThrow();
    }

    /**
     * V3-CHAT-003: 대기이고 만료 전인 등록 제안을 확정하고, 고른 항목만 등록 방법 '대화'로 약목록에 저장한다.
     * 사진 선택 저장(items 계약)과 같은 프로필 잠금 아래에서 저장하고, 금기 미리 받기(COMMON-005)는 커밋 뒤에 한다.
     * 하나라도 실패하면 상태 변경까지 모두 되돌린다.
     *
     * @param chosen 서비스가 카드와 맞춰 본 항목('이미 등록됨' 아님)
     * @throws NotPending 이미 확정·취소됐거나 만료돼 바꾼 행이 없음
     * @throws ChatProposalConflictException CONFLICT: 고른 약이 그사이 다른 경로로 약목록에 저장됨
     */
    @Transactional(timeout = 5)
    public ChatProposalResponse confirmRegister(long proposalId, long memberId, long profileId,
                                                List<ChatProposalResponse.Item> chosen) {
        drugCache.lockProfile(profileId);
        LocalDateTime now = now();
        if (jdbc.update("UPDATE chat_proposal SET status = 'CONFIRMED', decided_at = ? WHERE proposal_id = ? "
                + "AND member_id = ? AND kind = 'REGISTER' AND status = 'PENDING' AND expires_at > ?",
                now, proposalId, memberId, now) == 0) throw new NotPending();
        var codes = chosen.stream().map(ChatProposalResponse.Item::itemSeq).toList();
        if (!registeredAmong(profileId, codes).isEmpty())
            throw new ChatProposalConflictException(ChatProposalConflictException.Code.CONFLICT, null);
        for (var item : chosen) {
            var key = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("INSERT INTO my_drug(profile_id, item_seq, "
                        + "dose_per_time, dose_confirmed, source, registered_at, updated_at) VALUES(?,?,1,FALSE,?,?,?)",
                        Statement.RETURN_GENERATED_KEYS);
                statement.setLong(1, profileId);
                statement.setString(2, item.itemSeq());
                statement.setString(3, DrugSource.CHAT.name());
                statement.setObject(4, now);
                statement.setObject(5, now);
                return statement;
            }, key);
            jdbc.update("UPDATE chat_proposal_item SET chosen = TRUE, my_drug_id = ? WHERE proposal_id = ? AND item_no = ?",
                    Objects.requireNonNull(key.getKey()).longValue(), proposalId, item.itemNo());
        }
        events.publishEvent(new DrugRegistered(codes));
        return find(proposalId, memberId).orElseThrow();
    }

    /** V3-CHAT-004: 대기이고 만료 전인 제안을 취소한다. 아무것도 저장하지 않는다. 바꾼 행이 없으면 false다. */
    @Transactional(timeout = 5)
    public boolean cancel(long proposalId, long memberId) {
        LocalDateTime now = now();
        return jdbc.update("UPDATE chat_proposal SET status = 'CANCELLED', decided_at = ? WHERE proposal_id = ? "
                + "AND member_id = ? AND status = 'PENDING' AND expires_at > ?", now, proposalId, memberId, now) == 1;
    }

    /** 그 프로필 약목록에 이미 있는 품목 중 codes에 든 것. */
    @Transactional(readOnly = true)
    public List<String> registeredAmong(long profileId, List<String> codes) {
        if (codes.isEmpty()) return List.of();
        var args = new ArrayList<Object>();
        args.add(profileId);
        args.addAll(codes);
        return jdbc.queryForList("SELECT item_seq FROM my_drug WHERE profile_id = ? AND item_seq IN ("
                + String.join(",", codes.stream().map(code -> "?").toList()) + ")", String.class, args.toArray());
    }

    /**
     * V3-CHAT-005 정리 작업: 만료 시각이 지난 대기 제안을 '만료'로 바꾼다. 읽기·확정은 이 작업과 관계없이 만료 시각으로
     * 가르므로, 이 작업은 저장된 상태를 맞추기만 한다. decided_at은 만료 시각이다(읽을 때 보여 주는 값과 같음).
     */
    @Scheduled(fixedDelayString = "${chat.proposal.expire-interval-ms:600000}",
            initialDelayString = "${chat.proposal.expire-interval-ms:600000}")
    public void expireLapsed() {
        int expired = jdbc.update("UPDATE chat_proposal SET status = 'EXPIRED', decided_at = expires_at "
                + "WHERE status = 'PENDING' AND expires_at <= ? ORDER BY expires_at LIMIT " + EXPIRE_BATCH, now());
        if (expired > 0) log.info("[제안 만료] count={}", expired);
    }

    /** 조건부 UPDATE가 바꾼 행이 없음. 호출자가 지금 상태를 다시 읽어 409로 답한다. */
    public static class NotPending extends RuntimeException {
        NotPending() { super(null, null, false, false); }
    }

    /** 로그인 회원의 제안 하나. 없거나 다른 회원 것이면 빈 값이다. */
    @Transactional(readOnly = true)
    public Optional<ChatProposalResponse> find(Long proposalId, Long memberId) {
        var found = read(SELECT + " WHERE p.proposal_id = ? AND p.member_id = ? ORDER BY i.item_no", proposalId, memberId);
        return found.values().stream().findFirst();
    }

    /** 상담 기록의 약봉이 메시지에 붙은 제안. 메시지 번호 → 카드. 방 소유는 호출자가 이미 확인했다. */
    @Transactional(readOnly = true)
    public Map<Long, ChatProposalResponse> findByMessages(Collection<Long> messageIds, Long memberId) {
        if (messageIds.isEmpty()) return Map.of();
        String marks = String.join(",", messageIds.stream().map(id -> "?").toList());
        var args = new ArrayList<Object>(messageIds);
        args.add(memberId);
        var byMessage = new LinkedHashMap<Long, ChatProposalResponse>();
        read(SELECT + " WHERE p.message_id IN (" + marks + ") AND p.member_id = ? ORDER BY p.proposal_id, i.item_no",
                args.toArray()).values().forEach(proposal -> byMessage.put(proposal.messageId(), proposal));
        return byMessage;
    }

    private Map<Long, ChatProposalResponse> read(String sql, Object... args) {
        var heads = new LinkedHashMap<Long, Head>();
        jdbc.query(sql, (RowCallbackHandler) row -> {
            long id = row.getLong("proposal_id");
            var head = heads.computeIfAbsent(id, ignored -> Head.of(row));
            int itemNo = row.getInt("item_no");
            if (!row.wasNull()) head.items.add(item(row, itemNo));
        }, args);
        LocalDateTime now = now();
        var proposals = new LinkedHashMap<Long, ChatProposalResponse>();
        heads.forEach((id, head) -> proposals.put(id, ChatProposalResponse.of(id, head.kind, head.status, head.profileId,
                head.messageId, head.createdAt, head.expiresAt, head.decidedAt, head.items, now)));
        return proposals;
    }

    private static ChatProposalResponse.Item item(ResultSet row, int itemNo) throws SQLException {
        long myDrugId = row.getLong("my_drug_id");
        Long myDrug = row.wasNull() ? null : myDrugId;
        return new ChatProposalResponse.Item(itemNo, row.getString("item_seq"), row.getString("name"),
                row.getString("maker"), row.getString("image_url"), row.getString("mention"),
                StrengthMatch.valueOf(row.getString("strength_match")), row.getBoolean("already_registered"),
                row.getBoolean("chosen"), myDrug, row.getString("base_name"), row.getString("strength_text"));
    }

    static LocalDateTime now() {
        return LocalDateTime.now(TimeZoneConfig.ZONE).truncatedTo(ChronoUnit.SECONDS);
    }

    private record Head(ChatProposalResponse.Kind kind, ChatProposalResponse.Status status, Long profileId, Long messageId,
                        LocalDateTime createdAt, LocalDateTime expiresAt, LocalDateTime decidedAt,
                        List<ChatProposalResponse.Item> items) {
        static Head of(ResultSet row) {
            try {
                return new Head(ChatProposalResponse.Kind.valueOf(row.getString("kind")),
                        ChatProposalResponse.Status.valueOf(row.getString("status")), row.getLong("profile_id"),
                        row.getLong("message_id"), row.getObject("created_at", LocalDateTime.class),
                        row.getObject("expires_at", LocalDateTime.class),
                        row.getObject("decided_at", LocalDateTime.class), new ArrayList<>());
            } catch (SQLException exception) {
                throw new IllegalStateException(exception);
            }
        }
    }
}
