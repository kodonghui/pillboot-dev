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
        // '어제·제가'의 '제'를 약 이름 끝(수면제)으로 읽지 않게 먼저 뺀다.
        String q = question.replaceAll("(?<![가-힣])(엊그제|어제|그제|제가)", "").replaceAll("\\s+", "");
        // 많다는 말이 약을 가리킬 때만이다. '밥을 잔뜩 먹었어. 약 먹어도 돼?'는 약 질문으로 둔다.
        // '먹었을 때 어떻게 해?' 같은 가정과 '한 달 동안 나눠 먹었어' 같은 평소 복용은 모델 판단에 맡긴다.
        return q.matches(".*(약|제|정|캡슐|시럽|알)(을|를|이|가|도)?(다|전부|한꺼번에|한번에)?"
                + "((한통|한병|한줌|통째|몽땅|왕창|잔뜩|너무많이)(을|를|이나|쯤|정도|다|전부|모두|한꺼번에|한번에|그냥|방금|아까)*(먹었|드셨|삼켰|복용했)|과다복용(을)?했).*")
                && !q.matches(".*(먹었|드셨|삼켰|복용했|과다복용(을)?했)(을|으면|다면|다는|단|다고).*")
                && !q.matches(".*(동안|나눠|나누어|처방대로|꾸준히|매일|아니라|아니야|아닌데|안먹|않았).*");
    }

    /**
     * 실수로 먹었다고 밝힌 말. 이 말이면 자살예방 상담 안내가 빠지므로 좁게 본다.
     * 실수를 말한 뒤 같은 문장에서 먹었다고 해야 하고, 부정이나 자해를 뜻하는 말이 같이 있으면 들지 않는다.
     * 잘못 넓게 보면 109가 빠지고 좁게 보면 109가 더 나올 뿐이라 애매하면 들지 않는 쪽으로 둔다.
     */
    static boolean accidental(String question) {
        String q = question.replaceAll("\\s+", "");
        return q.matches(".*((실수로|실수해서|착각해서|착각하고|깜빡하고)[^.?!,]{0,15}(먹었|드셨|삼켰|복용했)|잘못(먹|드|삼)).*")
                && !q.matches(".*(죽|자살|자해|살기싫|살고싶지|그만살|끝내|사라지|없어지|일부러|힘들|괴로|고의|스스로|선택|작정|결심|아니|않).*");
    }

    /**
     * 2026-11-08 QA: 응급·자해 안내 뒤 30분 안에 앞 상황을 잇는 말(EmergencyFollowUp). 분류 모델이 일반 질문으로 넘겨도 서버가
     * 같은 안내로 막는다. 괜찮을지·증상·병원·119·먹은 시각·물이나 구토·농담·상담 번호·짧은 대답처럼 앞 상황에 기대는 말이고,
     * 넓게 잡아도 그 30분 안에 응급 안내가 한 번 더 나갈 뿐이다.
     */
    static boolean emergencyFollowUp(String question) {
        String q = question.replaceAll("\\s+", "");
        return q.matches(".*(괜찮|괜춘|살수|살아|살고|죽|병원|응급|119|구급|신고|부르|불러|토할|토하|토했|토가|구토|메스꺼|울렁"
                + "|어지러|어질|숨|가슴|아파|아프|졸려|졸리|잠이|의식|정신|기운|힘들|물을?많이|물마시|물마셔|우유|해독|전이야|전에먹"
                + "|됐어|지났|지나면|기다리|지켜보|싫어|싫은데|싫다|안가|못가|안갈|농담|장난|거짓말|아무도|상담|번호|전화).*")
                || q.matches("(응|ㅇㅇ|ㅇㅋ|오키|오케이|ok|okay|네|넵|예|그래|알겠어|알았어|알겠습니다|고마워|고맙습니다|감사|감사합니다"
                + "|미안|미안해|아니|아니야|됐어|그냥|그냥알려줘|알려줘)[.!~?ㅠㅜ]*");
    }

    /** 정해진 양보다 더 먹으려는지 묻는 말. 이미 먹었다는 말과 등록 일정 말은 여기에 들지 않는다. */
    static boolean extraDose(String question) {
        String q = question.replaceAll("\\s+", "");
        boolean more = q.matches(".*((?:두|세|네|[2-9])(?:알|정|개|캡슐|포)|(?:두|세|[2-9])배|한꺼번에|몰아서).*");
        boolean asking = q.matches(".*(먹으면|먹어도|먹을까|먹어야|복용하면|복용해도|복용할까|드시면|드셔도|드릴까|드려도).*");
        return more && asking || q.matches(".*더(먹으면|먹어도|먹을까|복용하면|복용해도|드시면|드셔도|드려도).*");
    }

    /**
     * 2026-11-08 카톡 기록('아침 점심 저녁, 복용할 양은 한알씩' 답에 '확인하지 못한 약' 금기 카드가 붙음): 시각·양·기간을
     * 정하는 말. 먹어도 되는지·같이 먹는지·부작용처럼 안전을 묻는 말이 섞이면 들지 않는다.
     */
    static boolean scheduleTalk(String question) {
        String q = normalized(question);
        if (coadministration(q) || q.matches(".*(먹어도|먹으면|복용해도|복용하면|괜찮|부작용|이상반응|주의|안전|위험|금기|상호작용|병용"
                + "|임신|임산부|수유|알레르기|음주|술|dur|DUR).*")) return false;
        return q.matches(".*([0-9]+시|아침|점심|저녁|자기전|일정|[0-9]+일(?:간|동안)|오늘부터|내일부터|한알|두알|세알"
                + "|[0-9]+(?:알|정|개|포|mL|ml|미리|cc)씩?|하루[0-9한두세]+번).*");
    }

    static boolean coadministration(String question) {
        String q = question.replaceAll("\\s+", "");
        return q.matches(".*(같이|함께|동시에)(먹|복용|사용|마시|마셔|드셔|드려).*" )
                || q.matches(".*(먹|복용|사용).*(같이|함께|동시에).*" );
    }
}
