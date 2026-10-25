import { errorOf } from './api/client.js'
import { getLiveSession, startLiveSession, stopLiveSession } from './api/live.js'

// SCH-04 AI 복약도우미 음성 대화(서버 Live API). 브라우저는 WebRTC 로 음성을 주고받고,
// 자막은 데이터 채널(oai-events)로, 저장 · 삭제 · 그만 먹기 결과는 서버 대화 상태 조회로 받는다.
// 순서와 정리 규칙은 서버 참고 구현(pillbadeum-server medication-live/live.js)을 따른다.
// 한 대화에 한 번만 쓴다. "다시 시작"은 새 LiveConversation 을 만든다. 화면은 subscribe · getSnapshot 으로 읽는다.
//
// phase: connecting(마이크 · 연결 준비) · live(대화 중) · completing(업무 완료, 완료 안내를 듣는 중)
//        done(완료 뒤 종료) · timeout(3분 초과) · busy(이미 대화 중, 409) · micDenied(마이크 거절) · unavailable · ended(사용자가 끝냄)

const pollInterval = 1500
const iceTimeout = 10000
// 완료 안내가 끝났는지 소리로 알 수 없을 때 기다리는 최대 시간(서버도 업무 성공 8초 뒤 정리한다)
const completionTimeout = 8000
const completionSilence = 700
const audibleLevel = 0.008
const completionPattern = /(등록|저장|삭제|변경).{0,24}(했|됐|되었|완료)|완료(됐|했|되었|예요|입니다)/

function waitForIce(peer) {
  if (peer.iceGatheringState === 'complete') return Promise.resolve()
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      peer.removeEventListener('icegatheringstatechange', changed)
      reject(new Error('ICE gathering timed out'))
    }, iceTimeout)
    function changed() {
      if (peer.iceGatheringState !== 'complete') return
      clearTimeout(timer)
      peer.removeEventListener('icegatheringstatechange', changed)
      resolve()
    }
    peer.addEventListener('icegatheringstatechange', changed)
  })
}

export class LiveConversation {
  // profileId · myDrugId · date: 서버에 넘기는 선택값(약은 없어도 된다).
  // onOperation(operation, savedSchedule): 저장 · 삭제 · 그만 먹기가 끝나면 한 번 부른다(달력 다시 불러오기).
  constructor({ profileId, myDrugId, date, onOperation }) {
    this.request = { selectedProfileId: profileId, selectedMyDrugId: myDrugId ?? null, selectedDate: date }
    this.onOperation = onOperation
    this.listeners = new Set()
    this.snapshot = {
      phase: 'connecting',
      status: null,
      missingFields: [],
      messages: [],
      deadline: null,
      now: Date.now(),
      operation: null,
      savedSchedule: null,
    }
    // 끝냈거나 버린 대화. 늦게 도착한 응답 · 이벤트는 무시한다.
    this.closed = false
    this.messageCount = 0
    this.lastCaptionAt = 0
    this.lastAudibleAt = 0
    this.completionText = ''
    this.completionCaptionAt = 0
    this.completionHeard = false
  }

  subscribe = (listener) => {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  getSnapshot = () => this.snapshot

  update(patch) {
    this.snapshot = { ...this.snapshot, ...patch }
    this.listeners.forEach((listener) => listener())
  }

  async start() {
    if (!window.isSecureContext || !navigator.mediaDevices?.getUserMedia || !window.RTCPeerConnection) {
      this.finish('unavailable')
      return
    }
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
      if (this.closed) {
        stream.getTracks().forEach((track) => track.stop())
        return
      }
      this.microphone = stream
    } catch (error) {
      this.finish(error?.name === 'NotAllowedError' || error?.name === 'SecurityError' ? 'micDenied' : 'unavailable')
      return
    }

    try {
      const peer = new RTCPeerConnection()
      this.peer = peer
      peer.addEventListener('track', (event) => this.play(event.track))
      peer.addEventListener('connectionstatechange', () => {
        if (['failed', 'disconnected'].includes(peer.connectionState)) this.lost()
      })
      this.microphone.getAudioTracks().forEach((track) => peer.addTrack(track, this.microphone))
      // 데이터 채널은 offer 를 만들기 전에 연다.
      this.channel = peer.createDataChannel('oai-events')
      this.channel.addEventListener('message', (event) => this.receive(event.data))
      await peer.setLocalDescription(await peer.createOffer())
      await waitForIce(peer)
      if (this.closed) return

      let session
      try {
        session = await startLiveSession({ sdp: peer.localDescription.sdp, ...this.request })
      } catch (error) {
        this.finish(errorOf(error).status === 409 ? 'busy' : 'unavailable')
        return
      }
      if (this.closed) {
        // 기다리는 사이 끝낸 대화가 늦게 열렸으면 바로 닫는다.
        stopLiveSession(session.sessionId).catch(() => {})
        return
      }
      this.sessionId = session.sessionId
      const deadline = Date.parse(session.expiresAt)
      window.addEventListener('pagehide', this.leave)
      this.update({ phase: 'live', deadline, now: Date.now() })
      await peer.setRemoteDescription({ type: 'answer', sdp: session.sdp })
      if (this.closed) return
      this.pollTimer = setInterval(() => this.poll(), pollInterval)
      this.clockTimer = setInterval(() => this.update({ now: Date.now() }), 1000)
      this.expiryTimer = setTimeout(() => this.expire(), Math.max(0, deadline - Date.now()))
      this.poll()
    } catch {
      if (!this.closed) this.lost()
    }
  }

  // 사용자가 "대화 끝내기" · 닫기를 눌렀거나 화면을 떠났다. 이미 저장한 일정은 되돌리지 않는다.
  end() {
    this.finish(this.snapshot.phase === 'completing' ? 'done' : 'ended')
  }

  // 데이터 채널 이벤트: 사용자 · AI 자막 조각과 세션 종료
  receive(data) {
    if (this.closed) return
    let event
    try {
      event = JSON.parse(data)
    } catch {
      return
    }
    if (event.type === 'session.input_transcript.delta') {
      if (this.snapshot.phase !== 'completing') {
        this.completionText = ''
        this.completionCaptionAt = 0
        this.completionHeard = false
      }
      this.caption('user', event.delta)
    } else if (event.type === 'session.output_transcript.delta') {
      this.caption('assistant', event.delta)
      this.lastCaptionAt = Date.now()
      this.completionText = (this.completionText + (event.delta ?? '')).slice(-160)
      if (!this.completionCaptionAt && completionPattern.test(this.completionText)) this.completionCaptionAt = Date.now()
    } else if (event.type === 'session.closed') {
      this.lost()
    }
  }

  caption(role, delta) {
    if (!delta) return
    const messages = this.snapshot.messages
    const last = messages.at(-1)
    if (last?.role === role) {
      this.update({ messages: [...messages.slice(0, -1), { ...last, text: last.text + delta }] })
    } else {
      this.messageCount += 1
      this.update({ messages: [...messages, { id: this.messageCount, role, text: delta }] })
    }
  }

  async poll() {
    if (this.closed || this.polling || !this.sessionId) return
    this.polling = true
    try {
      const progress = await getLiveSession(this.sessionId)
      if (this.closed) return
      this.update({ status: progress.status, missingFields: progress.missingFields ?? [] })
      const operation = progress.completedOperation ?? (progress.savedSchedule ? { action: 'SAVE' } : null)
      if (operation && !this.snapshot.operation) this.complete(operation, progress.savedSchedule)
      else if (progress.status === 'CLOSED') this.lost()
    } catch {
      if (!this.closed) this.lost()
    } finally {
      this.polling = false
    }
  }

  // 업무가 끝났다. 마이크를 끄고 완료 안내를 다 들은 뒤(또는 8초 뒤) 대화를 닫는다.
  complete(operation, savedSchedule) {
    this.update({ phase: 'completing', operation, savedSchedule: savedSchedule ?? null })
    this.microphone?.getAudioTracks().forEach((track) => {
      track.enabled = false
    })
    this.onOperation?.(operation, savedSchedule ?? null)
    this.completionTimer = setTimeout(() => this.finish('done'), completionTimeout)
  }

  expire() {
    this.finish(this.snapshot.phase === 'completing' ? 'done' : 'timeout')
  }

  // 연결이 끊기거나 서버가 대화를 닫았다. 업무를 마쳤으면 완료, 시간이 지났으면 시간 초과로 본다.
  lost() {
    if (this.closed) return
    if (this.snapshot.operation) this.finish('done')
    else if (this.snapshot.deadline && Date.now() >= this.snapshot.deadline) this.finish('timeout')
    else this.finish('unavailable')
  }

  play(track) {
    if (this.closed) return
    const stream = new MediaStream([track])
    const output = new Audio()
    output.autoplay = true
    output.srcObject = stream
    this.output = output
    output.play().catch(() => {})
    this.watchLevel(stream)
  }

  // 완료 안내 자막 뒤 실제 소리가 난 다음 0.7초 조용하면 끝난 것으로 본다(자막 간격만으로는 정하지 않는다).
  watchLevel(stream) {
    const AudioContextClass = window.AudioContext ?? window.webkitAudioContext
    if (!AudioContextClass) return
    try {
      const context = new AudioContextClass()
      const source = context.createMediaStreamSource(stream)
      const analyser = context.createAnalyser()
      analyser.fftSize = 2048
      const samples = new Float32Array(analyser.fftSize)
      const silent = context.createGain()
      silent.gain.value = 0
      source.connect(analyser)
      analyser.connect(silent)
      silent.connect(context.destination)
      context.resume().catch(() => {})
      this.level = { context, source }
      this.levelTimer = setInterval(() => {
        if (context.state !== 'running' || this.output?.paused) return
        analyser.getFloatTimeDomainData(samples)
        const level = Math.sqrt(samples.reduce((sum, sample) => sum + sample * sample, 0) / samples.length)
        const now = Date.now()
        if (level > audibleLevel) {
          this.lastAudibleAt = now
          if (this.completionCaptionAt) this.completionHeard = true
        }
        if (
          this.snapshot.phase === 'completing' &&
          this.completionHeard &&
          now - this.lastAudibleAt >= completionSilence &&
          now - this.lastCaptionAt >= completionSilence
        ) {
          this.finish('done')
        }
      }, 100)
    } catch {
      // 소리 분석을 못 하면 8초 뒤 종료로 대신한다.
    }
  }

  // 페이지를 떠날 때: 서버 대화를 닫고(keepalive) 마이크 · 연결을 정리한다.
  leave = () => {
    if (this.sessionId) stopLiveSession(this.sessionId, { keepalive: true }).catch(() => {})
    this.closed = true
    this.release()
  }

  finish(phase) {
    if (this.closed) return
    this.closed = true
    this.release()
    this.update({ phase })
    if (this.sessionId) stopLiveSession(this.sessionId).catch(() => {})
  }

  release() {
    clearInterval(this.pollTimer)
    clearInterval(this.clockTimer)
    clearInterval(this.levelTimer)
    clearTimeout(this.expiryTimer)
    clearTimeout(this.completionTimer)
    window.removeEventListener('pagehide', this.leave)
    this.microphone?.getTracks().forEach((track) => track.stop())
    this.channel?.close()
    this.peer?.close()
    if (this.output) {
      this.output.pause()
      this.output.srcObject = null
    }
    this.level?.source.disconnect()
    this.level?.context.close().catch(() => {})
  }
}
