package org.semiproject.pillbadeumserver.chat.service;

/**
 * 2026-11-08 카톡 기록('약목록 전부', '아니 전부 넣어달라고', '아까 말했잖아'를 앞 대화 없이 읽어 같은 것을 다시 물음):
 * 의도 가르기(V3-CHAT-001)에 함께 보내는 같은 방의 바로 앞 메시지 하나. 데이터로만 쓰고 저장하지 않는다.
 * 글은 앞에서부터 300자까지만 둔다(답변의 근거 표시·긴 설명은 의도에 필요 없음).
 *
 * @param user 회원이 보낸 말이면 true, 약봉이 답이면 false
 */
public record RecentTurn(boolean user, String text) {
    static final int MAX_TEXT = 300;

    public RecentTurn {
        text = text == null ? "" : text.strip();
        if (text.length() > MAX_TEXT) text = text.substring(0, MAX_TEXT);
    }
}
