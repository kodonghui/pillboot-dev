package org.semiproject.pillbadeumserver.medication.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.semiproject.pillbadeumserver.TestDatabaseSchema;
import org.semiproject.pillbadeumserver.common.security.JwtTokenProvider;
import org.semiproject.pillbadeumserver.medication.domain.MedicationReminderRepository;
import org.semiproject.pillbadeumserver.medication.domain.PushSubscriptionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP → 실제 트랜잭션 커밋 → 독립 JDBC 조회. 푸시 전송과 음성 호출은 하지 않는다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "MYSQL_TEST_URL", matches = ".+")
class MedicationNotificationFlowMySqlTests {
    private static final java.security.KeyPair KEY_PAIR = PushTestKeys.generate();
    private static final String SETTINGS = "/api/notification-settings";
    private static final String SUBSCRIPTIONS = "/api/push/subscriptions";
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JwtTokenProvider tokens;
    @Autowired ObjectMapper json;
    @Autowired MedicationReminderRepository reminders;
    @Autowired PushSubscriptionRepository subscriptions;
    @MockitoBean WebPushSender sender;
    private long owner, other, terms;
    private String version, endpoint;
    private LocalDateTime claimed;

    @DynamicPropertySource
    static void testKeys(DynamicPropertyRegistry registry) {
        registry.add("web-push.vapid.private-key", () -> PushTestKeys.privateText(KEY_PAIR));
        registry.add("web-push.vapid.public-key", () -> PushTestKeys.publicText(KEY_PAIR));
        registry.add("web-push.vapid.subject", () -> "mailto:fixture@example.test");
    }

    @BeforeEach
    void fixtures() throws Exception {
        try (var connection = dataSource.getConnection()) { TestDatabaseSchema.prepare(connection); }
        String suffix = UUID.randomUUID().toString();
        owner = member("notification-owner-" + suffix + "@example.test");
        other = member("notification-other-" + suffix + "@example.test");
        version = suffix.substring(0, 8);
        terms = insert("INSERT INTO consent(terms_type,terms_version,terms_title,terms_content,is_required,is_active,effective_at,created_at) "
                + "VALUES('MEDICATION_REMINDER',?,'fixture','fixture',FALSE,TRUE,CURRENT_TIMESTAMP - INTERVAL 1 SECOND,CURRENT_TIMESTAMP)", version);
        endpoint = "https://fcm.googleapis.com/fcm/send/fixture-" + suffix;
    }

    @AfterEach
    void cleanup() {
        if (owner != 0) {
            jdbc.update("DELETE FROM push_subscription WHERE member_id IN (?,?)", owner, other);
            jdbc.update("DELETE FROM consent_history WHERE member_id IN (?,?)", owner, other);
            jdbc.update("DELETE FROM notification_setting WHERE member_id IN (?,?)", owner, other);
            jdbc.update("DELETE FROM member WHERE member_id IN (?,?)", owner, other);
        }
        if (terms != 0) jdbc.update("DELETE FROM consent WHERE terms_id=?", terms);
        if (claimed != null) jdbc.update("DELETE FROM notification_dispatch WHERE dose_at=?", claimed);
        verifyNoInteractions(sender);
    }

    @Test
    void agreementRejectionRollsBackAndOnOffOnKeepsCorrectConsentHistory() throws Exception {
        mvc.perform(get(SETTINGS).header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pushEnabled").value(false))
                .andExpect(jsonPath("$.consentActive").value(false)).andExpect(jsonPath("$.consentPolicyVersion").value(version));
        for (String body : List.of("{\"pushEnabled\":true}", on("wrong"))) {
            mvc.perform(put(SETTINGS).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"));
            assertThat(count("SELECT COUNT(*) FROM notification_setting WHERE member_id=?", owner)).isZero();
            assertThat(count("SELECT COUNT(*) FROM consent_history WHERE member_id=?", owner)).isZero();
        }
        mvc.perform(put(SETTINGS).header("Authorization", bearer(owner)).param("memberId", Long.toString(other))
                        .contentType(MediaType.APPLICATION_JSON).content(on(version)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pushEnabled").value(true));
        assertThat(count("SELECT COUNT(*) FROM notification_setting WHERE member_id=? AND push_enabled=TRUE", owner)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM notification_setting WHERE member_id=?", other)).isZero();
        mvc.perform(put(SETTINGS).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON).content("{\"pushEnabled\":true}"))
                .andExpect(status().isOk());
        assertThat(count("SELECT COUNT(*) FROM consent_history WHERE member_id=? AND consent_status='AGREED'", owner)).isEqualTo(1);
        for (int i = 0; i < 2; i++) {
            mvc.perform(put(SETTINGS).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON).content("{\"pushEnabled\":false}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.pushEnabled").value(false));
        }
        assertThat(count("SELECT COUNT(*) FROM consent_history WHERE member_id=? AND consent_status='WITHDRAWN'", owner)).isEqualTo(1);
        mvc.perform(get(SETTINGS).header("Authorization", bearer(owner)))
                .andExpect(jsonPath("$.consentActive").value(false));
        mvc.perform(put(SETTINGS).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON).content(on(version)))
                .andExpect(status().isOk());
        assertThat(count("SELECT COUNT(*) FROM consent_history WHERE member_id=? AND consent_status='AGREED'", owner)).isEqualTo(2);
    }

    @Test
    void subscriptionUpsertPreservesOneRowTransfersOwnerAndOffDeletesOnlyCurrentOwnersSubscriptions() throws Exception {
        String body = subscription(endpoint, "AAAAAAAAAAAAAAAAAAAAAA");
        String response = mvc.perform(post(SUBSCRIPTIONS).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = json.readTree(response).path("subscriptionId").asLong();
        mvc.perform(post(SUBSCRIPTIONS).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.subscriptionId").value(id));
        assertThat(count("SELECT COUNT(*) FROM push_subscription WHERE member_id=?", owner)).isEqualTo(1);
        jdbc.update("UPDATE push_subscription SET created_at='2000-01-01 00:00:00' WHERE subscription_id=?", id);
        mvc.perform(post(SUBSCRIPTIONS).header("Authorization", bearer(other)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.subscriptionId").value(id));
        assertThat(count("SELECT COUNT(*) FROM push_subscription WHERE member_id=?", owner)).isZero();
        assertThat(count("SELECT COUNT(*) FROM push_subscription WHERE member_id=? AND created_at='2000-01-01 00:00:00'", other)).isEqualTo(1);
        mvc.perform(put(SETTINGS).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON).content("{\"pushEnabled\":false}"))
                .andExpect(status().isOk());
        assertThat(count("SELECT COUNT(*) FROM push_subscription WHERE member_id=?", other)).isEqualTo(1);
        mvc.perform(put(SETTINGS).header("Authorization", bearer(other)).contentType(MediaType.APPLICATION_JSON).content("{\"pushEnabled\":false}"))
                .andExpect(status().isOk());
        assertThat(count("SELECT COUNT(*) FROM push_subscription WHERE member_id=?", other)).isZero();
    }

    @Test
    void invalidSubscriptionAndUnauthenticatedWritesDoNotCreateRows() throws Exception {
        mvc.perform(post(SUBSCRIPTIONS).contentType(MediaType.APPLICATION_JSON).content(subscription(endpoint, "AAAAAAAAAAAAAAAAAAAAAA")))
                .andExpect(status().isUnauthorized());
        for (String body : List.of(subscription("http://127.0.0.1/fixture", "AAAAAAAAAAAAAAAAAAAAAA"),
                subscription(endpoint, "invalid"), subscription(endpoint, "AAAAAAAAAAAAAAAAAAAAAA").replace("p256dh", "missingKey"))) {
            mvc.perform(post(SUBSCRIPTIONS).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        assertThat(count("SELECT COUNT(*) FROM push_subscription WHERE member_id=?", owner)).isZero();
    }

    @Test
    void oldGoneResponseDoesNotDeleteRotatedSubscriptionAndSameMinuteIsClaimedOnce() throws Exception {
        mvc.perform(post(SUBSCRIPTIONS).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content(subscription(endpoint, "AAAAAAAAAAAAAAAAAAAAAA"))).andExpect(status().isCreated());
        long id = jdbc.queryForObject("SELECT subscription_id FROM push_subscription WHERE member_id=?", Long.class, owner);
        mvc.perform(post(SUBSCRIPTIONS).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content(subscription(endpoint, "AQEBAQEBAQEBAQEBAQEBAQ"))).andExpect(status().isOk());
        assertThat(subscriptions.deleteGone(id, "AAAAAAAAAAAAAAAAAAAAAA")).isZero();
        assertThat(count("SELECT COUNT(*) FROM push_subscription WHERE member_id=?", owner)).isEqualTo(1);
        assertThat(subscriptions.deleteGone(id, "AQEBAQEBAQEBAQEBAQEBAQ")).isEqualTo(1);
        claimed = LocalDateTime.of(2099, 1, 1, 0, 0).plusMinutes(Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 1_000_000));
        assertThat(reminders.claim(claimed, LocalDateTime.now())).isTrue();
        assertThat(reminders.claim(claimed, LocalDateTime.now())).isFalse();
    }

    private String on(String policy) { return "{\"pushEnabled\":true,\"notificationConsent\":{\"agreed\":true,\"policyVersion\":\"" + policy + "\"}}"; }
    private String subscription(String url, String auth) {
        return "{\"endpoint\":\"" + url + "\",\"keys\":{\"p256dh\":\"" + PushTestKeys.publicText(KEY_PAIR)
                + "\",\"auth\":\"" + auth + "\"},\"userAgent\":\"fixture\"}";
    }
    private String bearer(long member) { return "Bearer " + tokens.createAccessToken(member, "fixture@example.test", "ROLE_USER"); }
    private long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }
    private long member(String email) { return insert("INSERT INTO member(email,password_hash,nickname,user_role,created_at,updated_at) VALUES(?,'test-only','fixture','ROLE_USER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", email); }
    private long insert(String sql, Object... args) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, key);
        return key.getKey().longValue();
    }
}
