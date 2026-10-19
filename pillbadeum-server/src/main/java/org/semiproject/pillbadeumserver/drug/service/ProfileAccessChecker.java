package org.semiproject.pillbadeumserver.drug.service;

import org.semiproject.pillbadeumserver.common.exception.NotFoundException;
import org.semiproject.pillbadeumserver.common.security.SecurityUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/* 설명. 경로의 profileId 가 로그인한 회원의 프로필인지 확인한다. (임시 구현)
 *  ----------------------------------------------------------------------------------
 *  내 약 API 는 모두 /api/profiles/{profileId}/... 형태라서, 다른 회원의 profileId 를 넣으면
 *  남의 복용 약이 보이게 된다. 그래서 모든 서비스 메서드 맨 앞에서 checkOwner(profileId) 를 호출한다.
 *  ----------------------------------------------------------------------------------
 *  - 아직 profile 패키지에 Profile 엔티티 · Repository 가 없어서, profile 테이블의 member_id 만 SQL 로 읽는다.
 *  - 프로필 담당이 엔티티를 만들면 이 클래스 내부만 ProfileRepository 조회로 바꾼다.
 *    (호출하는 쪽 코드는 그대로 둘 수 있도록 메서드 이름과 예외를 유지한다)
 *  - 없는 프로필 → 404, 남의 프로필 → 403, 비로그인 → 401 (SecurityUtil)
 * */
@Component
public class ProfileAccessChecker {

    private static final String FIND_MEMBER_ID_SQL =
            "SELECT member_id FROM profile WHERE profile_id = ?";

    private final JdbcTemplate jdbcTemplate;

    public ProfileAccessChecker(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /* 목차. 1. 프로필이 있고, 로그인한 회원의 것인지 확인한다. 아니면 예외를 던진다. */
    public void checkOwner(Long profileId) {

        if (profileId == null) {
            throw new NotFoundException("존재하지 않는 프로필입니다.");
        }

        List<Long> memberIds = jdbcTemplate.queryForList(FIND_MEMBER_ID_SQL, Long.class, profileId);
        if (memberIds.isEmpty()) {
            throw new NotFoundException("존재하지 않는 프로필입니다.");
        }

        SecurityUtil.checkOwner(memberIds.get(0));
    }
}
