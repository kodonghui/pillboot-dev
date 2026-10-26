import test, { mock } from 'node:test'
import assert from 'node:assert/strict'

let readProgress
const stop = mock.fn(async () => {})
mock.module('../src/api/client.js', { namedExports: { errorOf: () => ({}) } })
mock.module('../src/api/live.js', {
  namedExports: {
    getLiveSession: (...args) => readProgress(...args),
    startLiveSession: async () => { throw new Error('This regression test must not open a real session') },
    stopLiveSession: stop,
  },
})
const { LiveConversation } = await import('../src/liveConversation.js')

function conversation(t, onOperation) {
  const previousWindow = globalThis.window
  globalThis.window = { removeEventListener() {} }
  const value = new LiveConversation({ profileId: 1, date: '2026-10-26', onOperation })
  value.sessionId = 'synthetic-regression-session'
  value.update({ phase: 'live', status: 'LISTENING' })
  t.after(() => {
    value.end()
    if (previousWindow === undefined) delete globalThis.window
    else globalThis.window = previousWindow
  })
  return value
}

test('처리 반복 조회는 시작 시각을 보존하고 확인·추가 정보 상태에서 초기화한다', async (t) => {
  let now = 1000
  t.mock.method(Date, 'now', () => now)
  let progress = { status: 'PROCESSING' }
  readProgress = async () => progress
  const live = conversation(t)
  await live.poll()
  assert.equal(live.getSnapshot().processingStartedAt, 1000)
  now = 16000
  live.update({ now }) // 실제 화면은 기존 1초 clockTimer로 같은 값을 갱신한다.
  await live.poll()
  assert.equal(live.getSnapshot().processingStartedAt, 1000)
  assert.equal(live.getSnapshot().now - live.getSnapshot().processingStartedAt, 15000)

  progress = { status: 'AWAITING_CONFIRMATION', message: '이대로 저장할까요?' }
  await live.poll()
  assert.equal(live.getSnapshot().processingStartedAt, null)
  assert.equal(live.getSnapshot().serverMessage, '이대로 저장할까요?')
  now = 40000
  progress = { status: 'PROCESSING' }
  await live.poll()
  assert.equal(live.getSnapshot().processingStartedAt, 40000)
  progress = { status: 'WAITING_FOR_DETAILS', missingFields: ['target'], message: '어떤 약인지 말씀해 주세요.' }
  await live.poll()
  assert.equal(live.getSnapshot().processingStartedAt, null)
  assert.deepEqual(live.getSnapshot().missingFields, ['target'])
  assert.equal(live.getSnapshot().serverMessage, '어떤 약인지 말씀해 주세요.')
})

test('저장 완료는 처리 표시를 지우고 마이크를 끄며 완료 콜백을 한 번만 호출한다', async (t) => {
  const completed = mock.fn()
  const live = conversation(t, completed)
  const track = { enabled: true, stop: mock.fn() }
  live.microphone = { getAudioTracks: () => [track], getTracks: () => [track] }
  live.update({ status: 'PROCESSING', processingStartedAt: Date.now() })
  const operation = { action: 'SAVE' }
  const savedSchedule = { profileId: 1, myDrugId: 1 }
  readProgress = async () => ({ status: 'SAVED', completedOperation: operation, savedSchedule })
  await live.poll()
  await live.poll()
  assert.equal(live.getSnapshot().phase, 'completing')
  assert.equal(live.getSnapshot().processingStartedAt, null)
  assert.equal(track.enabled, false)
  assert.equal(completed.mock.callCount(), 1)
  assert.deepEqual(completed.mock.calls[0].arguments, [operation, savedSchedule])
  live.end()
  assert.equal(live.getSnapshot().phase, 'done')
  assert.equal(track.stop.mock.callCount(), 1)
})

test('사용자가 끝낸 뒤 도착한 처리 응답은 대화나 경과 표시를 다시 열지 않는다', async (t) => {
  const live = conversation(t)
  let resolve
  readProgress = () => new Promise((done) => { resolve = done })
  const pending = live.poll()
  live.end()
  resolve({ status: 'PROCESSING', message: '확인 중' })
  await pending
  assert.equal(live.getSnapshot().phase, 'ended')
  assert.equal(live.getSnapshot().status, 'LISTENING')
  assert.equal(live.getSnapshot().processingStartedAt, null)
})
