package org.semiproject.pillbadeumserver.chat.service;

/** 기존 질문 처리 안에서 DB 기록과 의약 설명을 구분한다. 공개 API 분류값은 추가하지 않는다. */
final class ChatQuestionPurpose {
    private ChatQuestionPurpose() {}

    static boolean recordOnly(String question) {
        String q = normalized(question);
        // 명시적으로 끝낸 검색 절만 제외한다. 뒤의 실제 약 검색·용법·주의 질문은 그대로 검사한다.
        q = q.replaceFirst("^(?:이제)?(?:공식|공공데이터|약)?(?:검색|조회|찾기)(?:이야기|얘기)?(?:은|는|을|를)?(?:이제)?"
                + "(?:여기까지(?:하고)?|그만(?:하고|해|하자)?|하지말고|중단하고|멈추고)[,.!?]*", "");
        if (medicalQuestion(q) || q.matches(".*(검색|찾아|미등록|목록에없).*")) return false;
        return q.matches(".*(별명|기억용|대화기록|처음.*턴).*" )
                || (q.matches(".*(내|등록|기록|목록|입력|원래|아침|저녁|점심).*" )
                && q.matches(".*(목록|기록|일정|시각|시간|몇시|이름|용량|복용량|몇개|약뭐|뭐먹|뭐머|정리|구분|등록된약|아침약|저녁약|점심약).*"));
    }

    private static String normalized(String question) {
        return question.replaceAll("\\s+", "")
                .replaceAll("(?:권장량|권장방법)(?:말고|[을를]?묻는건아니야)", "");
    }

    static boolean pregnancy(String question) { return question.matches("(?s).*(임신|임산부|임부|수유).*" ); }

    static boolean medicalQuestion(String question) {
        String q = normalized(question);
        return coadministration(q)
                || q.matches(".*(효능|효과|약효|기전|성분|보관|겹|지속|흡수|대사|혈압|심박|출혈|위장|간기능|어떤약|무슨약|주의|부작용|이상반응|안전|위험|금기|상호작용|병용|용법|복용법|복용방법|먹는법|적절|권장|처방받|천식|당뇨|혈당|간질환|음주|술|임신|임산부|임부|수유|알레르기|중단|끊|중지|거르|건너뛰|놓쳤|잊었|두배|반알|반정|반으로|분할|쪼개|과량|과다|중복|대체).*" )
                || q.matches(".*(먹어도|먹으면|먹어야|복용해도|복용하면|복용해야|괜찮|공복|몇분|어떻게먹|어떻게복용).*" )
                || (q.matches(".*(식전|식후|식사|밥).*" ) && !q.matches(".*(등록|기록|입력).*"));
    }

    static boolean formatting(String question) {
        return !medicalQuestion(question) && !recordOnly(question)
                && normalized(question).matches(".*(표로|표형태|요약|짧게|간단히|한줄|두문장|한문장).*" );
    }

    /** 이미 많이 먹었다는 말. 분류 모델이 놓쳐도 응급 안내로 보낸다. '오늘 약 다 먹었어'는 들지 않는다. */
    static boolean overdoseTaken(String question) {
        String q = question.replaceAll("\\s+", "");
        // 많다는 말이 약을 가리킬 때만이다. '밥을 잔뜩 먹었어. 약 먹어도 돼?'는 약 질문으로 둔다.
        // '먹었을 때 어떻게 해?' 같은 가정과 '한 달 동안 나눠 먹었어' 같은 평소 복용은 모델 판단에 맡긴다.
        return q.matches(".*(약|제|정|캡슐|시럽|알)(을|를|이|가|도)?(다|전부|한꺼번에|한번에)?"
                + "(한통|한병|한줌|통째|몽땅|왕창|잔뜩|너무많이|과다복용).*(먹었|드셨|삼켰|복용했|했).*")
                && !q.matches(".*(먹었|드셨|삼켰|복용했|했)(을|으면|다면).*")
                && !q.matches(".*(동안|나눠|나누어|처방대로|꾸준히|매일).*");
    }

    static boolean accidental(String question) {
        return question.replaceAll("\\s+", "").matches(".*(실수|잘못|착각|모르고|깜빡).*");
    }

    /** 정해진 양보다 더 먹으려는지 묻는 말. 이미 먹었다는 말과 등록 일정 말은 여기에 들지 않는다. */
    static boolean extraDose(String question) {
        String q = question.replaceAll("\\s+", "");
        boolean more = q.matches(".*((?:두|세|네|[2-9])(?:알|정|개|캡슐|포)|(?:두|세|[2-9])배|한꺼번에|몰아서).*");
        boolean asking = q.matches(".*(먹으면|먹어도|먹을까|먹어야|복용하면|복용해도|복용할까|드시면|드셔도|드릴까|드려도).*");
        return more && asking || q.matches(".*더(먹으면|먹어도|먹을까|복용하면|복용해도|드시면|드셔도|드려도).*");
    }

    static boolean coadministration(String question) {
        String q = question.replaceAll("\\s+", "");
        return q.matches(".*(같이|함께|동시에)(먹|복용|사용|마시|마셔|드셔|드려).*" )
                || q.matches(".*(먹|복용|사용).*(같이|함께|동시에).*" );
    }
}
