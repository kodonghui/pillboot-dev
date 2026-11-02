package org.semiproject.pillbadeumserver.chat.service;

/** V3-CHAT-008: 진행 단계를 받는 쪽. 스트림 질문만 화면에 보내고, JSON 질문·카카오 중계는 아무것도 하지 않는다. */
@FunctionalInterface
public interface ChatProgress {
    ChatProgress NONE = stage -> { };

    void stage(ChatStage stage);
}
