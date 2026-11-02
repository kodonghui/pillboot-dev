package org.semiproject.pillbadeumserver.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.semiproject.pillbadeumserver.TestDatabaseSchema;
import org.semiproject.pillbadeumserver.chat.dto.ChatProposalConfirmRequest;
import org.semiproject.pillbadeumserver.chat.dto.ChatProposalResponse;
import org.semiproject.pillbadeumserver.chat.service.ChatDeadline;
import org.semiproject.pillbadeumserver.chat.service.ChatProposalConflictException;
import org.semiproject.pillbadeumserver.chat.service.ChatProposalService;
import org.semiproject.pillbadeumserver.chat.service.ChatProposalStore;
import org.semiproject.pillbadeumserver.chat.service.DurLookupService;
import org.semiproject.pillbadeumserver.chat.service.QuestionStore;
import org.semiproject.pillbadeumserver.chat.service.RegistrationCandidates;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.common.exception.NotFoundException;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * V3-CHAT-003~005(TASK-025): 등록 제안 확정·취소·만료를 전용 pill_test에서 확인함. 확정은 고른 항목만 '대화'로 저장하고,
 * 다시 확정·취소·만료·충돌은 409이며 아무것도 저장하지 않음. 금기 미리 받기는 가짜로 두고 불렸는지만 봄.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "MYSQL_TEST_URL", matches = ".+")
class ChatProposalDecisionMySqlTests {
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired QuestionStore questions;
    @Autowired ChatProposalStore store;
    @Autowired ChatProposalService service;
    @MockitoBean DurLookupService lookup;
    private long member, other, profile, session, proposalId;
    private String owned, first, second;

    @BeforeEach
    void prepare() throws Exception {
        try (var connection = dataSource.getConnection()) {
            TestDatabaseSchema.prepare(connection);
        }
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("pill_test");
        when(lookup.collectForPrefetch(any(), anySet(), any())).thenReturn(List.of());
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        owned = "decision-owned-" + suffix;
        first = "decision-first-" + suffix;
        second = "decision-second-" + suffix;
        member = memberRow();
        other = memberRow();
        profile = insert("INSERT INTO profile(member_id,profile_name,relationship_type,profile_birth_year,profile_gender,"
                + "profile_pregnant,created_at,updated_at) VALUES(?,'fixture','SELF',2000,'FEMALE',FALSE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", member);
        session = insert("INSERT INTO chat_session(member_id,profile_id,created_at,snap_birth_year,snap_gender) "
                + "VALUES(?,?,CURRENT_TIMESTAMP,2000,'FEMALE')", member, profile);
        jdbc.update("INSERT INTO drug(item_seq,name,maker,etc_otc,class_name,shape,color,form,material,synced_at) "
                + "VALUES(?,'가상혈압정5밀리그램','시험제약','ETC','synthetic','ROUND','WHITE','TABLET','synthetic',CURRENT_TIMESTAMP)", owned);
        insert("INSERT INTO my_drug(profile_id,item_seq,dose_per_time,source) VALUES(?,?,1,'SEARCH')", profile, owned);
        var question = questions.save(session, member, profile, "가상혈압정이랑 가상약정 등록해 줘");
        proposalId = store.saveRegisterAnswer(session, member, profile, question, "후보",
                List.of(candidate("가상혈압정", owned, "가상혈압정5밀리그램"), candidate("가상약정", first, "가상약정10밀리그램"),
                        candidate("가상약정", second, "가상약정20밀리그램")),
                ChatDeadline.after(Duration.ofSeconds(30)), false).proposal().proposalId();
        login(member);
    }

    @AfterEach
    void clean() {
        SecurityContextHolder.clearContext();
        // 제안·항목은 메시지를 지우면 함께 지워짐(ON DELETE CASCADE). 확정으로 만든 약은 프로필 약목록과 함께 지움.
        jdbc.update("DELETE FROM chat_message WHERE session_id=?", session);
        jdbc.update("DELETE FROM chat_session WHERE session_id=?", session);
        jdbc.update("DELETE FROM my_drug WHERE profile_id=?", profile);
        jdbc.update("DELETE FROM profile WHERE profile_id=?", profile);
        jdbc.update("DELETE FROM member WHERE member_id IN (?,?)", member, other);
        jdbc.update("DELETE FROM drug WHERE item_seq IN (?,?,?)", owned, first, second);
    }

    @Test
    void confirmSavesOnlyTheChosenItemsAsChatAndARepeatGetsTheFirstResult() {
        var confirmed = service.confirm(proposalId, choose(3));
        assertThat(confirmed.status()).isEqualTo(ChatProposalResponse.Status.CONFIRMED);
        assertThat(confirmed.decidedAt()).isNotNull();
        assertThat(confirmed.items()).extracting(ChatProposalResponse.Item::chosen).containsExactly(false, false, true);
        var saved = confirmed.items().get(2).myDrugId();
        assertThat(saved).isNotNull();
        assertThat(confirmed.items().get(1).myDrugId()).isNull();
        assertThat(jdbc.queryForMap("SELECT item_seq, source, dose_per_time FROM my_drug WHERE my_drug_id=?", saved))
                .containsEntry("item_seq", second).containsEntry("source", "CHAT").containsEntry("dose_per_time", 1);
        assertThat(drugCount()).isEqualTo(2);
        verify(lookup, timeout(2000)).collectForPrefetch(eq(List.of(second)), anySet(), any());

        assertThatThrownBy(() -> service.confirm(proposalId, choose(2)))
                .isInstanceOfSatisfying(ChatProposalConflictException.class, conflict -> {
                    assertThat(conflict.getCode()).isEqualTo(ChatProposalConflictException.Code.PROPOSAL_ALREADY_CONFIRMED);
                    assertThat(conflict.getProposal()).isEqualTo(confirmed);
                });
        assertThat(drugCount()).isEqualTo(2);
        assertThat(service.get(proposalId)).isEqualTo(confirmed);
    }

    @Test
    void invalidChoicesAreRejectedWithoutSavingAnything() {
        for (var request : List.of(new ChatProposalConfirmRequest(List.of()), choose(1), choose(9), choose(2, 2))) {
            assertThatThrownBy(() -> service.confirm(proposalId, request)).isInstanceOfSatisfying(BusinessException.class,
                    error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
        }
        assertThatThrownBy(() -> service.confirm(proposalId, null)).isInstanceOf(BusinessException.class);
        assertThat(storedStatus()).isEqualTo("PENDING");
        assertThat(drugCount()).isEqualTo(1);
        verify(lookup, after(150).never()).collectForPrefetch(any(), anySet(), any());
    }

    @Test
    void cancelSavesNothingRepeatsTheSameResultAndBlocksConfirm() {
        var cancelled = service.cancel(proposalId);
        assertThat(cancelled.status()).isEqualTo(ChatProposalResponse.Status.CANCELLED);
        assertThat(cancelled.decidedAt()).isNotNull();
        assertThat(service.cancel(proposalId)).isEqualTo(cancelled);
        assertThatThrownBy(() -> service.confirm(proposalId, choose(2)))
                .isInstanceOfSatisfying(ChatProposalConflictException.class, conflict -> {
                    assertThat(conflict.getCode()).isEqualTo(ChatProposalConflictException.Code.PROPOSAL_CANCELLED);
                    assertThat(conflict.getProposal()).isEqualTo(cancelled);
                });
        assertThat(drugCount()).isEqualTo(1);
    }

    @Test
    void confirmedProposalCannotBeCancelled() {
        service.confirm(proposalId, choose(2));
        assertThatThrownBy(() -> service.cancel(proposalId)).isInstanceOfSatisfying(ChatProposalConflictException.class,
                conflict -> assertThat(conflict.getCode()).isEqualTo(ChatProposalConflictException.Code.PROPOSAL_ALREADY_CONFIRMED));
        assertThat(storedStatus()).isEqualTo("CONFIRMED");
    }

    @Test
    void lapsedProposalIsRefusedAndTheCleanupStoresTheExpiry() {
        jdbc.update("UPDATE chat_proposal SET created_at = created_at - INTERVAL 31 MINUTE, "
                + "expires_at = expires_at - INTERVAL 31 MINUTE WHERE proposal_id=?", proposalId);
        assertThatThrownBy(() -> service.confirm(proposalId, choose(2))).isInstanceOfSatisfying(ChatProposalConflictException.class,
                conflict -> {
                    assertThat(conflict.getCode()).isEqualTo(ChatProposalConflictException.Code.PROPOSAL_EXPIRED);
                    assertThat(conflict.getProposal().status()).isEqualTo(ChatProposalResponse.Status.EXPIRED);
                });
        assertThatThrownBy(() -> service.cancel(proposalId)).isInstanceOfSatisfying(ChatProposalConflictException.class,
                conflict -> assertThat(conflict.getCode()).isEqualTo(ChatProposalConflictException.Code.PROPOSAL_EXPIRED));
        assertThat(drugCount()).isEqualTo(1);
        assertThat(storedStatus()).isEqualTo("PENDING");

        store.expireLapsed();
        assertThat(storedStatus()).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT decided_at = expires_at FROM chat_proposal WHERE proposal_id=?",
                Boolean.class, proposalId)).isTrue();
        assertThat(service.get(proposalId).status()).isEqualTo(ChatProposalResponse.Status.EXPIRED);
    }

    @Test
    void drugSavedElsewhereAfterTheCardKeepsItPendingWithAConflict() {
        insert("INSERT INTO my_drug(profile_id,item_seq,dose_per_time,source) VALUES(?,?,1,'SEARCH')", profile, first);
        assertThatThrownBy(() -> service.confirm(proposalId, choose(2, 3)))
                .isInstanceOfSatisfying(ChatProposalConflictException.class, conflict -> {
                    assertThat(conflict.getCode()).isEqualTo(ChatProposalConflictException.Code.CONFLICT);
                    assertThat(conflict.getProposal().status()).isEqualTo(ChatProposalResponse.Status.PENDING);
                });
        assertThat(storedStatus()).isEqualTo("PENDING");
        assertThat(drugCount()).as("두 번째 약도 저장하지 않음").isEqualTo(2);
        assertThat(service.confirm(proposalId, choose(3)).status()).isEqualTo(ChatProposalResponse.Status.CONFIRMED);
    }

    @Test
    void twoConfirmsAtOnceSaveOnce() throws Exception {
        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<Object>>();
        try (var pool = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    login(member);
                    start.await();
                    try {
                        return service.confirm(proposalId, choose(2, 3));
                    } catch (ChatProposalConflictException conflict) {
                        return conflict.getCode();
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                }));
            }
            start.countDown();
            var results = new ArrayList<Object>();
            for (var future : futures) results.add(future.get());
            assertThat(results).filteredOn(ChatProposalResponse.class::isInstance).hasSize(1);
            assertThat(results).contains(ChatProposalConflictException.Code.PROPOSAL_ALREADY_CONFIRMED);
        }
        assertThat(drugCount()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM my_drug WHERE profile_id=? AND source='CHAT'", Integer.class, profile))
                .isEqualTo(2);
    }

    @Test
    void otherMembersProposalIsTheSameAsAMissingOne() {
        login(other);
        assertThatThrownBy(() -> service.get(proposalId)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.confirm(proposalId, choose(2))).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.cancel(proposalId)).isInstanceOf(NotFoundException.class);
        assertThat(storedStatus()).isEqualTo("PENDING");
    }

    private static ChatProposalConfirmRequest choose(Integer... itemNos) {
        return new ChatProposalConfirmRequest(List.of(itemNos));
    }

    private int drugCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM my_drug WHERE profile_id=?", Integer.class, profile);
    }

    private String storedStatus() {
        return jdbc.queryForObject("SELECT status FROM chat_proposal WHERE proposal_id=?", String.class, proposalId);
    }

    private static RegistrationCandidates.Candidate candidate(String mention, String code, String name) {
        var drug = new PhotoCandidateData(code, name, "시험제약", "OTC", "synthetic", null, null, null, "ROUND", "WHITE",
                null, null, "TABLET", "synthetic", null, false);
        return new RegistrationCandidates.Candidate(mention, drug, StrengthMatch.UNKNOWN);
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
