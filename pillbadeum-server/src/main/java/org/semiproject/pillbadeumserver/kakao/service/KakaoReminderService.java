package org.semiproject.pillbadeumserver.kakao.service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.kakao.domain.KakaoLinkStore;
import org.semiproject.pillbadeumserver.kakao.dto.KakaoLinkDtos.KakaoReminderDrug;
import org.semiproject.pillbadeumserver.kakao.dto.KakaoLinkDtos.KakaoReminderTarget;
import org.springframework.stereotype.Service;

/**
 * V3-KAKAO-017(DEC-033): 다리가 이 날짜·이 분에 카톡으로 알릴 사람과 약을 묻는다. 연결마다 지금 고른 프로필의 약만 준다.
 * 기간·복용 시각 조건은 웹 푸시(MedicationReminderRepository.targets)와 같고, 웹 푸시 설정(push_enabled)은 보지 않는다.
 * 웹 푸시 발송과 웹 알림 기록은 바꾸지 않는다.
 */
@Service
public class KakaoReminderService {

    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern TIME = Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d");

    private final KakaoLinkStore store;

    public KakaoReminderService(KakaoLinkStore store) {
        this.store = store;
    }

    public List<KakaoReminderTarget> targets(String date, String time) {
        LocalDate day = date(date);
        if (time == null || !TIME.matcher(time).matches()) {
            throw invalid();
        }
        var grouped = new LinkedHashMap<String, KakaoReminderTarget>();
        for (var row : store.findReminderRows(day, time)) {
            grouped.computeIfAbsent(row.kakaoUserHmac(), user -> new KakaoReminderTarget(user, row.profileId(),
                            row.profileName(), new ArrayList<>()))
                    .drugs().add(new KakaoReminderDrug(row.myDrugId(), row.drugName()));
        }
        return List.copyOf(grouped.values());
    }

    private static LocalDate date(String value) {
        if (value == null || !DATE.matcher(value).matches()) {
            throw invalid();
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw invalid();
        }
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.INVALID_INPUT, "날짜는 YYYY-MM-DD, 시각은 HH:mm으로 보내 주세요.");
    }
}
