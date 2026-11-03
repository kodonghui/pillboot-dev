package org.semiproject.pillbadeumserver.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.semiproject.pillbadeumserver.TestDatabaseSchema;
import org.semiproject.pillbadeumserver.chat.service.ChatDeadline;
import org.semiproject.pillbadeumserver.chat.service.ChatProposalStore;
import org.semiproject.pillbadeumserver.chat.service.DurLookupService;
import org.semiproject.pillbadeumserver.chat.service.QuestionStore;
import org.semiproject.pillbadeumserver.chat.service.RegistrationCandidates;
import org.semiproject.pillbadeumserver.common.config.TimeZoneConfig;
import org.semiproject.pillbadeumserver.common.security.JwtTokenProvider;
import org.semiproject.pillbadeumserver.drug.domain.StrengthMatch;
import org.semiproject.pillbadeumserver.image.domain.PhotoCandidateData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * V3-KAKAO-002·005·010(TASK-033): 카카오 다리는 약봉이 계정 하나로 지인마다 프로필을 만들어 씀. 그 모양 그대로 기존 프로필
 * 만들기·제안 확정·취소·프로필 삭제 API가 지인끼리 섞이지 않게 동작하는지 전용 pill_test에서 확인함. 제안 카드는 AI 없이
 * 저장 경로로 직접 만들고, 금기 미리 받기는 가짜로 둠.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "MYSQL_TEST_URL", matches = ".+")
class KakaoBridgePathsMySqlTests {
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JwtTokenProvider tokens;
    @Autowired QuestionStore questions;
    @Autowired ChatProposalStore proposals;
    @MockitoBean DurLookupService lookup;
    private final List<Long> sessions = new ArrayList<>();
    private long bot, own;   // 약봉이 계정과 그 대표 프로필
    private String drug;

    @BeforeEach
    void prepare() throws Exception {
        try (var connection = dataSource.getConnection()) {
            TestDatabaseSchema.prepare(connection);
        }
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("pill_test");
        when(lookup.collectForPrefetch(any(), anySet(), any())).thenReturn(List.of());
        drug = "kakao-path-" + UUID.randomUUID().toString().substring(0, 8);
        bot = insert("INSERT INTO member(email,password_hash,nickname,user_role,created_at,updated_at) "
                + "VALUES(?,'synthetic','yakbong','ROLE_USER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", "yakbong-" + UUID.randomUUID() + "@example.test");
        own = profile("약봉이", "SELF");
        jdbc.update("INSERT INTO drug(item_seq,name,maker,etc_otc,class_name,shape,color,form,material,synced_at) "
                + "VALUES(?,'가상감기정','시험제약','OTC','synthetic','ROUND','WHITE','TABLET','synthetic',CURRENT_TIMESTAMP)", drug);
    }

    @AfterEach
    void clean() {
        // 제안·항목은 메시지·프로필을 지우면 함께 지워짐(ON DELETE CASCADE). 지운 프로필의 방은 벡터 정리 요청만 남음.
        for (long session : sessions) {
            jdbc.update("DELETE FROM chat_message WHERE session_id=?", session);
            jdbc.update("DELETE FROM chat_session WHERE session_id=?", session);
            jdbc.update("DELETE FROM vector_cleanup WHERE session_id=?", session);
        }
        jdbc.update("DELETE FROM my_drug WHERE profile_id IN (SELECT profile_id FROM profile WHERE member_id=?)", bot);
        jdbc.update("DELETE FROM profile WHERE member_id=?", bot);
        jdbc.update("DELETE FROM member WHERE member_id=?", bot);
        jdbc.update("DELETE FROM drug WHERE item_seq=?", drug);
    }

    @Test
    void eachFriendGetsAnOtherProfileUnderTheBotAccountAndABadValueSavesNothing() throws Exception {
        long first = createFriend(friend("엄마친구", 1965, "FEMALE"));
        // 같은 호칭이어도 지인마다 따로 만듦. 같은 지인이 두 번 연결되지 않게 하는 일은 다리의 연결 기록이 맡음.
        long second = createFriend(friend("엄마친구", 1958, "UNKNOWN"));
        assertThat(first).isNotEqualTo(second);
        assertThat(jdbc.queryForList("SELECT relationship_type FROM profile WHERE member_id=? AND profile_id IN (?,?)",
                String.class, bot, first, second)).containsExactly("OTHER", "OTHER");
        assertThat(jdbc.queryForObject("SELECT profile_gender FROM profile WHERE profile_id=?", String.class, second))
                .isEqualTo("UNKNOWN");
        mvc.perform(get("/api/profiles").header("Authorization", bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));

        // 호칭 10자 초과·올해 뒤 출생연도는 400이고 아무것도 생기지 않음. 다리는 그 값만 다시 물음(V3-KAKAO-002).
        int nextYear = LocalDate.now(TimeZoneConfig.ZONE).getYear() + 1;
        for (String body : new String[]{friend("가나다라마바사아자차카", 1965, "FEMALE"), friend("이웃", nextYear, "MALE")}) {
            mvc.perform(post("/api/profiles").header("Authorization", bearer())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM profile WHERE member_id=?", Integer.class, bot)).isEqualTo(3);
    }

    @Test
    void eachFriendsCardCarriesItsProfileAndTheSecondConfirmIsAlreadyHandled() throws Exception {
        long friend = profile("엄마친구", "OTHER"), neighbor = profile("이웃", "OTHER");
        long card = registerCard(friend), other = registerCard(neighbor);

        // 다리는 빠른 답장을 받은 지인의 연결 프로필과 카드의 프로필이 같을 때만 확정·취소함(V3-KAKAO-005).
        mvc.perform(get("/api/chat-proposals/{id}", card).header("Authorization", bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.profileId").value(friend));
        // '모두 등록'을 두 번 누르면 첫 번째만 저장되고, 두 번째는 이미 처리됨(지금 카드를 함께 줌).
        confirm(card).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));
        confirm(card).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROPOSAL_ALREADY_CONFIRMED"))
                .andExpect(jsonPath("$.proposal.profileId").value(friend));
        assertThat(drugsOf(friend)).isEqualTo(1);
        assertThat(drugsOf(neighbor)).isZero();

        mvc.perform(post("/api/chat-proposals/{id}/cancel", other).header("Authorization", bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(drugsOf(neighbor)).isZero();
        assertThat(drugsOf(own)).isZero();
    }

    @Test
    void deletingAFriendRemovesOnlyThatFriendsDrugsChatsAndCardsAndASecondDeleteIsNotFound() throws Exception {
        long friend = profile("엄마친구", "OTHER"), neighbor = profile("이웃", "OTHER");
        long confirmed = registerCard(friend);
        confirm(confirmed).andExpect(status().isOk());
        long pending = registerCard(friend);
        long kept = registerCard(neighbor);
        assertThat(drugsOf(friend)).isEqualTo(1);

        mvc.perform(delete("/api/profiles/{id}", friend).header("Authorization", bearer())).andExpect(status().isNoContent());
        assertThat(count("SELECT COUNT(*) FROM chat_proposal WHERE proposal_id IN (?,?)", confirmed, pending)).isZero();
        assertThat(count("SELECT COUNT(*) FROM chat_session WHERE profile_id=?", friend)).isZero();
        assertThat(count("SELECT COUNT(*) FROM my_drug WHERE profile_id=?", friend)).isZero();
        assertThat(count("SELECT COUNT(*) FROM profile WHERE profile_id=?", friend)).isZero();
        // 다른 지인의 카드·대화는 그대로임.
        assertThat(count("SELECT COUNT(*) FROM chat_proposal WHERE proposal_id=? AND status='PENDING'", kept)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM chat_message m JOIN chat_session s USING(session_id) WHERE s.profile_id=?", neighbor))
                .isEqualTo(2);

        // 이미 없는 프로필은 404임. 다리는 이를 지워진 것으로 보고 해제 기록의 프로필 ID를 지움(V3-KAKAO-010).
        mvc.perform(delete("/api/profiles/{id}", friend).header("Authorization", bearer()))
                .andExpect(status().isNotFound());
        // 약봉이 계정의 마지막 프로필은 기존 규칙대로 지울 수 없음.
        mvc.perform(delete("/api/profiles/{id}", neighbor).header("Authorization", bearer())).andExpect(status().isNoContent());
        mvc.perform(delete("/api/profiles/{id}", own).header("Authorization", bearer()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LAST_PROFILE"));
        assertThat(count("SELECT COUNT(*) FROM profile WHERE member_id=?", bot)).isEqualTo(1);
    }

    private long createFriend(String body) throws Exception {
        var response = mvc.perform(post("/api/profiles").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.relationshipType").value("OTHER"))
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(response.replaceAll(".*\"profileId\":(\\d+).*", "$1"));
    }

    /** 다리가 보내는 모양. 임신 여부는 남성이면 '아니오', 여성·모름이면 물어 받은 답(DEC-016). */
    private static String friend(String name, int birthYear, String gender) {
        return "{\"profileName\":\"" + name + "\",\"relationshipType\":\"OTHER\",\"profileBirthYear\":" + birthYear
                + ",\"profileGender\":\"" + gender + "\",\"profilePregnant\":false}";
    }

    /** 지인의 대화방에 '가상감기정 등록해 줘' 질문과 후보 하나짜리 등록 카드를 저장함. */
    private long registerCard(long profileId) {
        long session = jdbc.queryForList("SELECT session_id FROM chat_session WHERE profile_id=?", Long.class, profileId)
                .stream().findFirst().orElseGet(() -> {
                    long created = insert("INSERT INTO chat_session(member_id,profile_id,created_at,snap_birth_year,snap_gender) "
                            + "VALUES(?,?,CURRENT_TIMESTAMP,1965,'FEMALE')", bot, profileId);
                    sessions.add(created);
                    return created;
                });
        var question = questions.save(session, bot, profileId, "가상감기정 등록해 줘");
        var drugData = new PhotoCandidateData(drug, "가상감기정", "시험제약", "OTC", "synthetic", null, null, null, "ROUND",
                "WHITE", null, null, "TABLET", "synthetic", null, false);
        return proposals.saveRegisterAnswer(session, bot, profileId, question, "등록할 약 후보를 찾았어요.",
                List.of(new RegistrationCandidates.Candidate("가상감기정", drugData, StrengthMatch.UNKNOWN)),
                ChatDeadline.after(Duration.ofSeconds(30)), false).proposal().proposalId();
    }

    private ResultActions confirm(long proposalId) throws Exception {
        return mvc.perform(post("/api/chat-proposals/{id}/confirm", proposalId).header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"itemNos\":[1]}"));
    }

    private long profile(String name, String relation) {
        return insert("INSERT INTO profile(member_id,profile_name,relationship_type,profile_birth_year,profile_gender,"
                + "profile_pregnant,created_at,updated_at) VALUES(?,?,?,1965,'FEMALE',FALSE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                bot, name, relation);
    }

    private int drugsOf(long profileId) {
        return count("SELECT COUNT(*) FROM my_drug WHERE profile_id=?", profileId);
    }

    private int count(String sql, Object... values) {
        return jdbc.queryForObject(sql, Integer.class, values);
    }

    private String bearer() {
        return "Bearer " + tokens.createAccessToken(bot, "yakbong@example.test", "ROLE_USER");
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
