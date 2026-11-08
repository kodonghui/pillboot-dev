import client, { apiBase, bearer, expireOn401 } from './client.js'

// 질문 · 다시 보내기의 답변 대기 한도. 서버는 85초 안에 답을 끝내므로 90초가 지나면 연결이 끊긴 것으로 본다(목업 CHT-02-11).
const answerTimeout = 90_000

// 서버 오류를 axios 오류와 같은 모양({ response: { status, data } })으로 만든다. 화면은 errorOf 로 같이 읽는다.
function responseError(status, data) {
  const error = new Error(data?.message ?? `HTTP ${status}`)
  error.response = { status, data }
  return error
}

// SSE 본문에서 이벤트 하나(빈 줄로 끝나는 덩어리)를 읽는다. 주석 줄(:keep-alive)은 건너뛴다.
function parseEvent(block) {
  let type = 'message'
  const data = []
  for (const line of block.split('\n')) {
    if (line.startsWith('event:')) type = line.slice(6).trim()
    else if (line.startsWith('data:')) data.push(line.slice(line.startsWith('data: ') ? 6 : 5))
  }
  return { type, data: data.join('\n') }
}

// 응답을 조금씩 읽어 stage 이벤트마다 onStage 를 부르고, result 본문을 돌려준다(V3-CHAT-008).
// 스트림을 열기 전 오류(401 · 400 · 403 · 404 · 409)는 기존 JSON 오류로 오고, 연 뒤 실패는 error 이벤트 하나로 온다.
// result 없이 닫히거나 90초가 지나거나 signal 로 끊으면 응답 없는 오류로 끝난다(화면이 상담 기록으로 저장 여부를 확인한다).
async function streamAnswer(path, body, { onStage, signal }) {
  const controller = new AbortController()
  const abort = () => controller.abort()
  const timer = setTimeout(abort, answerTimeout)
  if (signal?.aborted) abort()
  signal?.addEventListener('abort', abort)
  const authorization = bearer()
  try {
    const response = await fetch(`${apiBase}${path}`, {
      method: 'POST',
      headers: {
        Accept: 'text/event-stream, application/json',
        'Content-Type': 'application/json',
        ...(authorization && { Authorization: authorization }),
      },
      body: JSON.stringify(body),
      signal: controller.signal,
    })
    if (!response.ok) {
      const data = await response.json().catch(() => null)
      expireOn401(response.status, data?.code, authorization)
      throw responseError(response.status, data)
    }
    const reader = response.body.pipeThrough(new TextDecoderStream()).getReader()
    let buffer = ''
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += value.replace(/\r\n?/g, '\n')
      let end
      while ((end = buffer.indexOf('\n\n')) >= 0) {
        const event = parseEvent(buffer.slice(0, end))
        buffer = buffer.slice(end + 2)
        if (event.type === 'stage') onStage(JSON.parse(event.data).stage)
        else if (event.type === 'result') return JSON.parse(event.data)
        else if (event.type === 'error') {
          const data = JSON.parse(event.data)
          throw responseError(data.status, data)
        }
      }
    }
    throw new Error('답변 스트림이 결과 없이 닫혔어요.')
  } finally {
    clearTimeout(timer)
    signal?.removeEventListener('abort', abort)
  }
}

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

// QuestionStreamController.submit — POST /api/chat-sessions/{sessionId}/messages/stream
// 결과 · 오류 본문은 JSON 경로(QuestionController.submit)와 같고, 답을 만드는 동안 서버가 들어선 단계를 onStage(stage) 로 알린다.
// signal: 화면을 떠나면 끊는다
export function askQuestionStream(sessionId, content, { onStage, signal }) {
  return streamAnswer(`/api/chat-sessions/${sessionId}/messages/stream`, { content }, { onStage, signal })
}

// QuestionController.retry — POST /api/chat-sessions/{sessionId}/messages/{messageId}/retry
export async function retryQuestion(sessionId, messageId) {
  const { data } = await client.post(`/api/chat-sessions/${sessionId}/messages/${messageId}/retry`, undefined, {
    timeout: answerTimeout,
  })
  return data
}

// ChatPhotoController.submit — POST /api/chat-sessions/{sessionId}/photos
// 처방전 · 약봉투 사진 1장(multipart image). 결과는 질문 보내기와 같은 ANSWERED 모양이고 스트림 경로는 없다.
export async function sendChatPhoto(sessionId, file) {
  const form = new FormData()
  form.append('image', file)
  const { data } = await client.post(`/api/chat-sessions/${sessionId}/photos`, form, { timeout: answerTimeout })
  return data
}

// ChatProposalController.read — GET /api/chat-proposals/{proposalId}
export async function getChatProposal(proposalId) {
  const { data } = await client.get(`/api/chat-proposals/${proposalId}`)
  return data
}

// ChatProposalController.confirm — POST /api/chat-proposals/{proposalId}/confirm
// itemNos: 등록 제안에서 고른 항목 번호. 일정 제안은 넘기지 않는다(본문 없음).
// applyPrescription: 처방전 사진에서 읽은 1회 투약량 · 1일 횟수 · 총 일수로 일정도 함께 넣기(2026-11-08, 등록 카드만)
export async function confirmChatProposal(proposalId, itemNos, applyPrescription) {
  const body = itemNos ? { itemNos, ...(applyPrescription ? { applyPrescription: true } : {}) } : undefined
  const { data } = await client.post(`/api/chat-proposals/${proposalId}/confirm`, body)
  return data
}

// ChatProposalController.cancel — POST /api/chat-proposals/{proposalId}/cancel
export async function cancelChatProposal(proposalId) {
  const { data } = await client.post(`/api/chat-proposals/${proposalId}/cancel`)
  return data
}
