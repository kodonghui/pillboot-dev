package org.semiproject.pillbadeumserver.chat.service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import org.semiproject.pillbadeumserver.chat.domain.ChatSession;
import org.semiproject.pillbadeumserver.chat.domain.ChatSessionRepository;
import org.semiproject.pillbadeumserver.chat.dto.ChatSessionListResponse;
import org.semiproject.pillbadeumserver.chat.dto.ChatSessionResponse;
import org.semiproject.pillbadeumserver.common.config.TimeZoneConfig;
import org.semiproject.pillbadeumserver.common.exception.NotFoundException;
import org.semiproject.pillbadeumserver.common.security.SecurityUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatSessionService {

    private final ChatSessionRepository sessions;

    public ChatSessionService(ChatSessionRepository sessions) {
        this.sessions = sessions;
    }

    @Transactional(readOnly = true)
    public ChatSessionListResponse list(Long profileId) {
        Long memberId = SecurityUtil.getCurrentMemberId();
        Long ownerId = sessions.findProfileOwner(profileId)
                .orElseThrow(() -> new NotFoundException("프로필을 찾을 수 없습니다."));
        SecurityUtil.checkOwner(ownerId);

        return ChatSessionListResponse.from(
                sessions.findAllByMemberIdAndProfileIdOrderByUpdatedAtDescSessionIdDesc(memberId, profileId));
    }

    @Transactional
    public Creation createOrReuse(Long profileId) {
        Long memberId = SecurityUtil.getCurrentMemberId();
        // 프로필 삭제와 동일하게 회원 → 프로필 순서로 잠가 FK 삽입과 삭제가 교착하지 않게 함.
        sessions.lockMember(memberId).orElseThrow(() -> new NotFoundException("회원을 찾을 수 없습니다."));
        Long ownerId = sessions.lockProfileOwner(profileId)
                .orElseThrow(() -> new NotFoundException("프로필을 찾을 수 없습니다."));
        SecurityUtil.checkOwner(ownerId);

        // MySQL DATETIME의 초 단위 정밀도와 맞춰 생성/재사용 응답의 createdAt을 동일하게 유지한다.
        LocalDateTime now = LocalDateTime.now(TimeZoneConfig.ZONE).truncatedTo(ChronoUnit.SECONDS);
        var empty = sessions.findEmptySession(memberId, profileId);
        if (empty.isPresent()) {
            ChatSession session = empty.get();
            session.reopen(now);
            return new Creation(ChatSessionResponse.from(session), false);
        }

        // 소유권 확인 뒤에만 건강 관련 프로필 값을 읽는다.
        var profile = sessions.findProfileSnapshot(profileId, memberId)
                .orElseThrow(() -> new NotFoundException("프로필을 찾을 수 없습니다."));
        ChatSession session = ChatSession.create(memberId, profileId, profile.getBirthYear(),
                profile.getGender(), profile.getPregnantFlag() == 1, now);
        return new Creation(ChatSessionResponse.from(sessions.save(session)), true);
    }

    public record Creation(ChatSessionResponse session, boolean created) {}
}
