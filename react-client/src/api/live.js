import client from './client.js'

// MedicationLiveController.start — POST /api/medication/live/sessions
export async function startLiveSession(request) {
  const { data } = await client.post('/api/medication/live/sessions', request)
  return data
}

// MedicationLiveController.get — GET /api/medication/live/sessions/{sessionId}
export async function getLiveSession(sessionId) {
  const { data } = await client.get(`/api/medication/live/sessions/${encodeURIComponent(sessionId)}`)
  return data
}

// MedicationLiveController.stop — DELETE /api/medication/live/sessions/{sessionId}
// keepalive: 페이지를 떠나는 중에도 요청이 끝까지 가도록 fetch 의 keepalive 로 보낸다.
export async function stopLiveSession(sessionId, { keepalive = false } = {}) {
  await client.delete(
    `/api/medication/live/sessions/${encodeURIComponent(sessionId)}`,
    keepalive ? { adapter: 'fetch', fetchOptions: { keepalive: true } } : undefined,
  )
}
