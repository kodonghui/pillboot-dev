package org.semiproject.pillbadeumserver.profile.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProfileRepository extends JpaRepository<Profile, Long> {

    /* 로그인 한 회원의 프로필 목록 */
    List<Profile> findByMember_MemberIdOrderByProfileIdAsc(Long memberId);

    /* 해당 회원 소유의 프로필 한 개 */
    Optional<Profile> findByProfileIdAndMember_MemberId(Long profileId, Long memberId);

    /* 마지막 프로필 삭제 방지를 위한 회원의 프로필 개수 조회  */
    long countByMember_MemberId(Long memberId);

    /* 프로필 삭제 시 해당 프로필의 등록 약 삭제 */
    @Modifying
    @Query(value="DELETE FROM my_drug WHERE profile_id = :profileId", nativeQuery = true)
    void deleteMyDrugsByProfileId(@Param("profileId") Long profileId);

    /* 채팅 관련 삭제 */
    /* 1. 프로필 삭제 시 해당 프로필 채팅방의 메세지 삭제 */
    @Modifying
    @Query(value="DELETE FROM chat_message WHERE session_id IN (" +
                    "SELECT session_id FROM chat_session WHERE profile_id = :profileId" +
                    ")", nativeQuery = true)
    void deleteChatMessagesByProfileId(@Param("profileId") Long profileId);

    /* 2. 프로필 삭제 시 해당 프로필 채팅방의 요약 삭제 */
    @Modifying
    @Query(value = "DELETE FROM chat_summary WHERE session_id IN (" +
                    "SELECT session_id FROM chat_session WHERE profile_id = :profileId" +
                    ")",  nativeQuery = true)
    void deleteChatSummariesByProfileId(@Param("profileId") Long profileId);

    /* 3. 프로필 삭제 시 해당 프로필의 채팅방 삭제 */
    @Modifying
    @Query(value = "DELETE FROM chat_session WHERE profile_id = :profileId", nativeQuery = true)
    void deleteChatSessionsByProfileId(@Param("profileId") Long profileId);


}
