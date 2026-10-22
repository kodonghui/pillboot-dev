import client from './client.js'

// 질문 · 다시 보내기의 답변 대기 한도. 서버는 85초 안에 답을 끝내므로 90초가 지나면 연결이 끊긴 것으로 본다(목업 CHT-02-11).
const answerTimeout = 90_000

// ChatSessionController.list — GET /api/profiles/{profileId}/chat-sessions
export async function getChatSessions(profileId) {
  const { data } = await client.get(`/api/profiles/${profileId}/chat-sessions`)
  return data.sessions
}

// ChatSessionController.create — POST /api/profiles/{profileId}/chat-sessions
export async function createChatSession(profileId) {
  const { data } = await client.post(`/api/profiles/${profileId}/chat-sessions`)
  return data
}

// MessageHistoryController.read — GET /api/chat-sessions/{sessionId}/messages
export async function getChatMessages(sessionId, beforeTurn) {
  const { data } = await client.get(`/api/chat-sessions/${sessionId}/messages`, { params: { beforeTurn } })
  return data
}

// QuestionController.submit — POST /api/chat-sessions/{sessionId}/messages
export async function askQuestion(sessionId, content) {
  const { data } = await client.post(`/api/chat-sessions/${sessionId}/messages`, { content }, { timeout: answerTimeout })
  return data
}

// QuestionController.retry — POST /api/chat-sessions/{sessionId}/messages/{messageId}/retry
export async function retryQuestion(sessionId, messageId) {
  const { data } = await client.post(`/api/chat-sessions/${sessionId}/messages/${messageId}/retry`, undefined, {
    timeout: answerTimeout,
  })
  return data
}
