package org.semiproject.pillbadeumserver.chat.service;

import java.util.List;
import org.semiproject.pillbadeumserver.chat.dto.QuestionResponse;
import org.semiproject.pillbadeumserver.common.exception.ExternalApiException;
import org.semiproject.pillbadeumserver.drug.domain.StrengthMatch;
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
        PhotoIdentificationWorkflow.PrescriptionRead reading;
        try {
            reading = photos.readPrescription(profileId, image);
        } catch (ExternalApiException unavailable) {
            log.warn("[사진 제안] result=identify_failed");
            return proposals.savePhotoTurn(sessionId, memberId, profileId, TURN, FAILED, List.of(), deadline);
        }
        var read = reading.response();
        try {
            var groups = read.drugs() == null ? List.<PhotoIdentificationResponse.DrugGroup>of() : read.drugs();
            var mentions = groups.stream().map(PhotoIdentificationResponse.DrugGroup::readName).toList();
            var found = groups.stream().map(PhotoProposalService::candidates).toList();
            var picked = RegistrationProposalService.pick(found);
            log.info("[사진 제안] names={} rows={} items={} byCode={}", groups.size(), reading.rowCount(), picked.size(),
                    groups.stream().filter(g -> Boolean.TRUE.equals(g.codeMatched())).count());
            String content = describe(mentions, found, picked.isEmpty()) + notes(groups, reading.rowCount());
            return proposals.savePhotoTurn(sessionId, memberId, profileId, TURN, content, picked, deadline);
        } finally {
            if (read.photoId() != null) discard(read.photoId());
        }
    }

    /** 읽은 이름이 없으면 다시 찍기, 후보가 하나도 없으면 읽은 이름과 함께 글로 알려 달라는 안내, 있으면 등록 요청과 같은 설명. */
    static String describe(List<String> mentions, List<List<RegistrationCandidates.Candidate>> found, boolean nothingPicked) {
        if (mentions.isEmpty()) return RETAKE;
        if (nothingPicked) return NOT_FOUND + "\n읽은 이름: " + String.join(", ", mentions.stream().map(RegistrationProposalService::quote).toList()) + ".";
        return RegistrationProposalService.describe(mentions, found);
    }

    /** 카톡 다리가 이 줄을 골라 보여 준다(카톡 다리 proposal.py). 문구를 바꾸면 다리도 함께 바꾼다. */
    static final String PARTIAL = "처방전 표에서";
    static final String UNCLEAR = "글자가 흐려 확실하지 않은 이름:";

    /**
     * 2026-11-08: 표에서 본 줄보다 읽은 약이 적으면 몇 줄 중 몇 개만 읽었는지, 흐리게 읽은 이름이 있으면 그 이름을 덧붙인다.
     * 처방전 사진을 몇 번 다시 보내도 같은 3개만 읽던 일(카톡 기록)을 사용자가 알 수 있게 한다.
     */
    static String notes(List<PhotoIdentificationResponse.DrugGroup> groups, int rowCount) {
        var out = new StringBuilder();
        if (rowCount > groups.size() && !groups.isEmpty())
            out.append("\n").append(PARTIAL).append(" 약 ").append(rowCount).append("줄을 봤는데 ").append(groups.size())
                    .append("개만 읽었어요. 나머지 약은 이름이나 보험코드(9자리)를 글로 보내 주세요.");
        var unclear = groups.stream().filter(g -> Boolean.TRUE.equals(g.unclear()))
                .map(g -> RegistrationProposalService.quote(g.readName())).toList();
        if (!unclear.isEmpty())
            out.append("\n").append(UNCLEAR).append(" ").append(String.join(", ", unclear)).append(". 고르기 전에 포장과 맞는지 봐 주세요.");
        return out.toString();
    }

    /**
     * 판독 응답의 후보(일치·확인 불가·다름 순)를 이름마다 최대 5개 카드 후보로 바꾼다. 약 캐시에 없는 행은 쓰지 않는다.
     * 2026-11-08: 보험코드로 맞춘 하나뿐인 후보는 함량 일치로 두어 미리 고르고, 비슷한 이름·흐린 이름의 후보는 확인 불가로
     * 두어 미리 고르지 않는다. 처방전에서 읽은 1회 투약량·횟수·일수는 후보마다 붙인다(확정 때 '처방대로 일정'에 씀).
     */
    static List<RegistrationCandidates.Candidate> candidates(PhotoIdentificationResponse.DrugGroup group) {
        boolean byCode = Boolean.TRUE.equals(group.codeMatched());
        boolean similar = Boolean.TRUE.equals(group.similar());
        boolean unclear = Boolean.TRUE.equals(group.unclear());
        return group.candidates().stream().filter(c -> c.itemSeq() != null && c.name() != null)
                .limit(RegistrationCandidates.PER_MENTION)
                .map(c -> new RegistrationCandidates.Candidate(group.readName(),
                        new PhotoCandidateData(c.itemSeq(), c.name(), c.maker(), c.etcOtc(), null, c.imageUrl(),
                                c.printFront(), c.printBack(), c.shape(), c.color(), null, null, null, null,
                                c.printMatched(), c.cancelled()),
                        byCode ? StrengthMatch.MATCH
                                : similar || unclear || c.strengthMatch() == null ? StrengthMatch.UNKNOWN : c.strengthMatch(),
                        similar, group.prescription()))
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
