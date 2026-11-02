package org.semiproject.pillbadeumserver.chat.service;

import java.util.List;
import org.semiproject.pillbadeumserver.chat.dto.QuestionResponse;
import org.semiproject.pillbadeumserver.common.exception.ExternalApiException;
import org.semiproject.pillbadeumserver.drug.domain.StrengthMatch;
import org.semiproject.pillbadeumserver.image.domain.ImagePhotoKind;
import org.semiproject.pillbadeumserver.image.domain.PhotoCandidateData;
import org.semiproject.pillbadeumserver.image.domain.PhotoIdentificationResponse;
import org.semiproject.pillbadeumserver.image.service.PhotoIdentificationWorkflow;
import org.semiproject.pillbadeumserver.image.service.PhotoLifecycleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * V3-CHAT-006 상담방 사진으로 등록 제안. 판독·후보 찾기는 기존 처방전 사진 식별을 그대로 쓰고(장수·형식 제한·사진 속 글자는
 * 데이터로만), 결과를 등록 제안 카드로 바꿔 회원 턴과 함께 저장한다. 약목록에는 저장하지 않으며 사진은 처리 뒤 바로 지운다.
 * 답변 글은 모델이 만들지 않은 고정 문장이다.
 */
@Service
public class PhotoProposalService {
    private static final Logger log = LoggerFactory.getLogger(PhotoProposalService.class);
    static final String TURN = "처방전·약봉투 사진을 보냈어요";
    static final String RETAKE = "사진에서 약 이름을 읽지 못했어요. 글자가 잘 보이게 밝은 곳에서 다시 찍어 보내 주시거나, "
            + "약 이름과 함량을 글로 알려 주세요.";
    static final String NOT_FOUND = "사진에서 읽은 약을 공공데이터에서 찾지 못했어요. 포장에 적힌 제품명과 함량을 글로 알려 주세요. "
            + "예) 타이레놀 500 등록해 줘";
    static final String FAILED = "지금은 사진을 확인할 수 없어요. 잠시 뒤 다시 보내 주시거나 약 이름과 함량을 글로 알려 주세요. "
            + "약목록에는 아무것도 저장하지 않았어요.";

    private final PhotoIdentificationWorkflow photos;
    private final PhotoLifecycleService lifecycle;
    private final ChatProposalStore proposals;

    public PhotoProposalService(PhotoIdentificationWorkflow photos, PhotoLifecycleService lifecycle, ChatProposalStore proposals) {
        this.photos = photos;
        this.lifecycle = lifecycle;
        this.proposals = proposals;
    }

    /**
     * 파일 형식·크기·권한 오류는 아무것도 저장하지 않고 그대로 던진다. 판독·공공데이터 장애는 안내 답변으로 남긴다.
     * @return 회원 턴과 답변을 저장한 ANSWERED. 고른 후보가 있으면 답변에 등록 제안 카드가 붙는다.
     */
    public QuestionResponse propose(Long sessionId, Long memberId, Long profileId, MultipartFile image, ChatDeadline deadline) {
        PhotoIdentificationResponse read;
        try {
            read = photos.identify(profileId, ImagePhotoKind.PRESCRIPTION, List.of(image));
        } catch (ExternalApiException unavailable) {
            log.warn("[사진 제안] result=identify_failed");
            return proposals.savePhotoTurn(sessionId, memberId, profileId, TURN, FAILED, List.of(), deadline);
        }
        try {
            var groups = read.drugs() == null ? List.<PhotoIdentificationResponse.DrugGroup>of() : read.drugs();
            var mentions = groups.stream().map(PhotoIdentificationResponse.DrugGroup::readName).toList();
            var found = groups.stream().map(PhotoProposalService::candidates).toList();
            var picked = RegistrationProposalService.pick(found);
            log.info("[사진 제안] names={} items={}", groups.size(), picked.size());
            return proposals.savePhotoTurn(sessionId, memberId, profileId, TURN, describe(mentions, found, picked.isEmpty()),
                    picked, deadline);
        } finally {
            if (read.photoId() != null) discard(read.photoId());
        }
    }

    /** 읽은 이름이 없으면 다시 찍기, 후보가 하나도 없으면 읽은 이름과 함께 글로 알려 달라는 안내, 있으면 등록 요청과 같은 설명. */
    static String describe(List<String> mentions, List<List<RegistrationCandidates.Candidate>> found, boolean nothingPicked) {
        if (mentions.isEmpty()) return RETAKE;
        if (nothingPicked) return NOT_FOUND + "\n읽은 이름: " + String.join(", ", mentions.stream().map(name -> "'" + name + "'").toList()) + ".";
        return RegistrationProposalService.describe(mentions, found);
    }

    /** 판독 응답의 후보(일치·확인 불가·다름 순)를 이름마다 최대 5개 카드 후보로 바꾼다. 약 캐시에 없는 행은 쓰지 않는다. */
    static List<RegistrationCandidates.Candidate> candidates(PhotoIdentificationResponse.DrugGroup group) {
        return group.candidates().stream().filter(c -> c.itemSeq() != null && c.name() != null)
                .limit(RegistrationCandidates.PER_MENTION)
                .map(c -> new RegistrationCandidates.Candidate(group.readName(),
                        new PhotoCandidateData(c.itemSeq(), c.name(), c.maker(), c.etcOtc(), null, c.imageUrl(),
                                c.printFront(), c.printBack(), c.shape(), c.color(), null, null, null, null,
                                c.printMatched(), c.cancelled()),
                        c.strengthMatch() == null ? StrengthMatch.UNKNOWN : c.strengthMatch()))
                .toList();
    }

    /** 카드에는 품목코드만 남으므로 사진·후보 행은 바로 지운다. 실패하면 기존 정리 작업이 30분 뒤 지운다. */
    private void discard(long photoId) {
        try {
            lifecycle.deleteOwned(photoId);
        } catch (RuntimeException failure) {
            log.warn("[사진 제안] cleanup=deferred type={}", failure.getClass().getSimpleName());
        }
    }
}
