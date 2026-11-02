package org.semiproject.pillbadeumserver.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.semiproject.pillbadeumserver.TestDatabaseSchema;
import org.semiproject.pillbadeumserver.chat.dto.ChatProposalResponse;
import org.semiproject.pillbadeumserver.chat.dto.MessageHistoryResponse;
import org.semiproject.pillbadeumserver.chat.service.ChatDeadline;
import org.semiproject.pillbadeumserver.chat.service.ChatProposalStore;
import org.semiproject.pillbadeumserver.chat.service.MessageHistoryService;
import org.semiproject.pillbadeumserver.chat.service.QuestionStore;
import org.semiproject.pillbadeumserver.chat.service.RegistrationCandidates;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.common.security.CustomUserDetails;
import org.semiproject.pillbadeumserver.drug.domain.StrengthMatch;
import org.semiproject.pillbadeumserver.image.domain.PhotoCandidateData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

/**
 * V3-CHAT-002(TASK-024): 등록 제안 카드가 답변 메시지와 함께 저장되고 상담 기록에 보이며, 약목록에는 아무것도 저장되지 않음을
 * 전용 pill_test에서 확인함. 후보 찾기·AI는 쓰지 않고 공식 후보 모양의 값을 직접 넣음.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "MYSQL_TEST_URL", matches = ".+")
class RegistrationProposalMySqlTests {
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired QuestionStore questions;
    @Autowired ChatProposalStore proposals;
    @Autowired MessageHistoryService history;
    private long member, other, profile, session;
    private String owned, fresh;

    @BeforeEach
    void prepare() throws Exception {
        try (var connection = dataSource.getConnection()) {
            TestDatabaseSchema.prepare(connection);
        }
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("pill_test");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        owned = "proposal-owned-" + suffix;
        fresh = "proposal-fresh-" + suffix;
        member = memberRow();
        other = memberRow();
        profile = insert("INSERT INTO profile(member_id,profile_name,relationship_type,profile_birth_year,profile_gender,"
                + "profile_pregnant,created_at,updated_at) VALUES(?,'fixture','SELF',2000,'FEMALE',FALSE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", member);
        session = insert("INSERT INTO chat_session(member_id,profile_id,created_at,snap_birth_year,snap_gender) "
                + "VALUES(?,?,CURRENT_TIMESTAMP,2000,'FEMALE')", member, profile);
        jdbc.update("INSERT INTO drug(item_seq,name,maker,etc_otc,class_name,shape,color,form,material,synced_at) "
                + "VALUES(?,'가상혈압정5밀리그램','시험제약','ETC','synthetic','ROUND','WHITE','TABLET','synthetic',CURRENT_TIMESTAMP)", owned);
        insert("INSERT INTO my_drug(profile_id,item_seq,dose_per_time,source) VALUES(?,?,1,'SEARCH')", profile, owned);
    }

    @AfterEach
    void clean() {
        SecurityContextHolder.clearContext();
        // 제안·항목은 메시지를 지우면 함께 지워짐(ON DELETE CASCADE).
        jdbc.update("DELETE FROM chat_message WHERE session_id=?", session);
        jdbc.update("DELETE FROM chat_session WHERE session_id=?", session);
        jdbc.update("DELETE FROM my_drug WHERE profile_id=?", profile);
        jdbc.update("DELETE FROM profile WHERE profile_id=?", profile);
        jdbc.update("DELETE FROM member WHERE member_id IN (?,?)", member, other);
        jdbc.update("DELETE FROM drug WHERE item_seq IN (?,?)", owned, fresh);
    }

    @Test
    void cardIsSavedWithTheAnswerAndShownInHistoryButNothingIsAddedToTheDrugList() {
        var question = questions.save(session, member, profile, "가상혈압정이랑 가상약정 10mg 등록해 줘");
        var answer = proposals.saveRegisterAnswer(session, member, profile, question, "등록할 약 후보를 찾았어요.",
                List.of(candidate("가상혈압정", owned, "가상혈압정5밀리그램", StrengthMatch.UNKNOWN),
                        candidate("가상약정 10mg", fresh, "가상약정10밀리그램", StrengthMatch.MATCH)),
                ChatDeadline.after(Duration.ofSeconds(30)), false);

        var card = answer.proposal();
        assertThat(card.kind()).isEqualTo(ChatProposalResponse.Kind.REGISTER);
        assertThat(card.status()).isEqualTo(ChatProposalResponse.Status.PENDING);
        assertThat(card.profileId()).isEqualTo(profile);
        assertThat(card.messageId()).isEqualTo(answer.messageId());
        assertThat(Duration.between(card.createdAt(), card.expiresAt())).isEqualTo(Duration.ofMinutes(30));
        assertThat(card.decidedAt()).isNull();
        assertThat(card.schedule()).isNull();
        assertThat(card.items()).extracting(ChatProposalResponse.Item::itemNo).containsExactly(1, 2);
        var first = card.items().get(0);
        assertThat(first.itemSeq()).isEqualTo(owned);
        assertThat(first.alreadyRegistered()).isTrue();
        assertThat(first.mention()).isEqualTo("가상혈압정");
        var second = card.items().get(1);
        assertThat(second.alreadyRegistered()).isFalse();
        assertThat(second.chosen()).isFalse();
        assertThat(second.myDrugId()).isNull();
        assertThat(second.strengthMatch()).isEqualTo(StrengthMatch.MATCH);
        // 후보 약은 약 캐시에만 들어가고(함량 칸 포함), 약목록은 그대로임.
        assertThat(second.name()).isEqualTo("가상약정10밀리그램");
        assertThat(second.baseName()).isEqualTo("가상약정");
        assertThat(second.strengthText()).isEqualTo("10밀리그램");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM my_drug WHERE profile_id=?", Integer.class, profile)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT content FROM chat_message WHERE message_id=?", String.class, answer.messageId()))
                .isEqualTo("등록할 약 후보를 찾았어요.");

        login(member);
        var messages = history.read(session, null, 30).messages();
        assertThat(messages).extracting(MessageHistoryResponse.Message::role)
                .containsExactly(MessageHistoryResponse.Role.USER, MessageHistoryResponse.Role.ASSISTANT);
        assertThat(messages.get(0).proposal()).isNull();
        assertThat(messages.get(1).proposal().proposalId()).isEqualTo(card.proposalId());
        assertThat(messages.get(1).proposal().items()).hasSize(2);

        // 다른 회원에게는 없는 제안과 같음(V3-COMMON-003).
        assertThat(proposals.find(card.proposalId(), other)).isEmpty();
        assertThat(proposals.find(card.proposalId(), member)).isPresent();
    }

    @Test
    void pendingCardPastItsExpiryIsReadAsExpired() {
        var question = questions.save(session, member, profile, "가상약정 등록해 줘");
        var card = proposals.saveRegisterAnswer(session, member, profile, question, "후보",
                List.of(candidate("가상약정", fresh, "가상약정10밀리그램", StrengthMatch.UNKNOWN)),
                ChatDeadline.after(Duration.ofSeconds(30)), false).proposal();
        jdbc.update("UPDATE chat_proposal SET created_at = created_at - INTERVAL 31 MINUTE, "
                + "expires_at = expires_at - INTERVAL 31 MINUTE WHERE proposal_id=?", card.proposalId());
        var read = proposals.find(card.proposalId(), member).orElseThrow();
        assertThat(read.status()).isEqualTo(ChatProposalResponse.Status.EXPIRED);
        assertThat(read.decidedAt()).isEqualTo(read.expiresAt());
        assertThat(jdbc.queryForObject("SELECT status FROM chat_proposal WHERE proposal_id=?", String.class, card.proposalId()))
                .as("읽기만으로 저장된 상태를 바꾸지 않음").isEqualTo("PENDING");
    }

    @Test
    void secondCardForTheSameQuestionIsRejectedWithoutSavingAnything() {
        var question = questions.save(session, member, profile, "가상약정 등록해 줘");
        var deadline = ChatDeadline.after(Duration.ofSeconds(30));
        var items = List.of(candidate("가상약정", fresh, "가상약정10밀리그램", StrengthMatch.UNKNOWN));
        proposals.saveRegisterAnswer(session, member, profile, question, "후보", items, deadline, false);
        assertThatThrownBy(() -> proposals.saveRegisterAnswer(session, member, profile, question, "후보", items, deadline, false))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_REQUEST));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_proposal WHERE profile_id=?", Integer.class, profile)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_message WHERE session_id=? AND role='assistant'",
                Integer.class, session)).isEqualTo(1);
    }

    @Test
    void photoTurnSavesTheMemberTurnAnswerAndCardTogetherWithoutTouchingTheDrugList() {
        // V3-CHAT-006: 사진 후보는 판독 때 약 캐시에 들어간 품목임.
        jdbc.update("INSERT INTO drug(item_seq,name,maker,etc_otc,class_name,shape,color,form,material,synced_at) "
                + "VALUES(?,'가상약정10밀리그램','시험제약','OTC','synthetic','ROUND','WHITE','TABLET','synthetic',CURRENT_TIMESTAMP)", fresh);
        var turn = proposals.savePhotoTurn(session, member, profile, "처방전·약봉투 사진을 보냈어요", "등록할 약 후보를 찾았어요.",
                List.of(candidate("가상혈압정", owned, "가상혈압정5밀리그램", StrengthMatch.UNKNOWN),
                        candidate("가상약정10mg", fresh, "가상약정10밀리그램", StrengthMatch.MATCH)),
                ChatDeadline.after(Duration.ofSeconds(30)));
        assertThat(turn.result()).isEqualTo(org.semiproject.pillbadeumserver.chat.dto.QuestionResponse.Result.ANSWERED);
        assertThat(turn.answer().turnNo()).isEqualTo(turn.question().turnNo());
        var card = turn.answer().proposal();
        assertThat(card.status()).isEqualTo(ChatProposalResponse.Status.PENDING);
        assertThat(card.items()).extracting(ChatProposalResponse.Item::alreadyRegistered).containsExactly(true, false);
        assertThat(card.items()).extracting(ChatProposalResponse.Item::mention).containsExactly("가상혈압정", "가상약정10mg");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM my_drug WHERE profile_id=?", Integer.class, profile)).isEqualTo(1);

        var notice = proposals.savePhotoTurn(session, member, profile, "처방전·약봉투 사진을 보냈어요", "다시 찍어 주세요.",
                List.of(), ChatDeadline.after(Duration.ofSeconds(30)));
        assertThat(notice.answer().proposal()).isNull();
        login(member);
        var messages = history.read(session, null, 30).messages();
        assertThat(messages).extracting(MessageHistoryResponse.Message::content)
                .containsExactly("처방전·약봉투 사진을 보냈어요", "등록할 약 후보를 찾았어요.", "처방전·약봉투 사진을 보냈어요", "다시 찍어 주세요.");
        assertThat(messages.get(1).proposal().proposalId()).isEqualTo(card.proposalId());
        assertThat(messages.get(3).proposal()).isNull();
    }

    @Test
    void savingACardLocksTheMemberBeforeTheProfileLikeConfirming() throws Exception {
        // 확정(confirmRegister)은 회원 → 프로필 순서로 잠근다. 카드 저장이 프로필을 먼저 잡으면 둘이 서로 기다려 교착한다.
        var items = List.of(candidate("가상약정", fresh, "가상약정10밀리그램", StrengthMatch.UNKNOWN));
        var question = questions.save(session, member, profile, "가상약정 등록해 줘");
        waitsForTheMemberWithoutHoldingTheProfile(() -> proposals.saveRegisterAnswer(session, member, profile, question,
                "후보", items, ChatDeadline.after(Duration.ofSeconds(30)), false));
        waitsForTheMemberWithoutHoldingTheProfile(() -> proposals.savePhotoTurn(session, member, profile,
                "처방전·약봉투 사진을 보냈어요", "후보", items, ChatDeadline.after(Duration.ofSeconds(30))));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_proposal WHERE profile_id=?", Integer.class, profile)).isEqualTo(2);
    }

    private void waitsForTheMemberWithoutHoldingTheProfile(java.util.concurrent.Callable<?> save) throws Exception {
        try (var confirming = dataSource.getConnection(); var probe = dataSource.getConnection();
             var worker = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            confirming.setAutoCommit(false);
            try (var lock = confirming.prepareStatement("SELECT member_id FROM member WHERE member_id=? FOR UPDATE")) {
                lock.setLong(1, member);
                lock.executeQuery().close();
            }
            var saving = worker.submit(save);
            Thread.sleep(500);
            assertThat(saving.isDone()).as("회원 잠금을 기다림").isFalse();
            probe.setAutoCommit(false);
            try (var lock = probe.prepareStatement("SELECT profile_id FROM profile WHERE profile_id=? FOR UPDATE NOWAIT")) {
                lock.setLong(1, profile);
                lock.executeQuery().close(); // 저장이 프로필을 먼저 잡았다면 여기서 3572(NOWAIT)
            }
            probe.rollback();
            confirming.rollback();
            saving.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    private static RegistrationCandidates.Candidate candidate(String mention, String code, String name, StrengthMatch strength) {
        var drug = new PhotoCandidateData(code, name, "시험제약", "OTC", "synthetic", null, null, null, "ROUND", "WHITE",
                null, null, "TABLET", "synthetic", null, false);
        return new RegistrationCandidates.Candidate(mention, drug, strength);
    }

    private static void login(long memberId) {
        var user = new CustomUserDetails(memberId, "fixture@example.test", "ROLE_USER");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }

    private long memberRow() {
        return insert("INSERT INTO member(email,password_hash,nickname,user_role,created_at,updated_at) "
                + "VALUES(?,'synthetic','fixture','USER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", UUID.randomUUID() + "@example.test");
    }

    private long insert(String sql, Object... values) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            return statement;
        }, key);
        return key.getKey().longValue();
    }
}
