const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../src/main/resources/medication-live/live.js'), 'utf8');
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; };
const response = (body, status = 200) => ({ status, ok: status >= 200 && status < 300, json: async () => body });
const tick = () => new Promise(resolve => setImmediate(resolve));

function environment({ media, fetcher, audioSupported = false } = {}) {
  const elements = new Map(), requests = [], peers = [];
  const timers = new Map();
  let stops = 0, now = Date.parse('2026-10-19T00:00:00Z'), timerId = 0, amplitude = 0, audioClosed = 0;
  class TestDate extends Date {
    constructor(...args) { if (args.length) super(...args); else super(now); }
    static now() { return now; }
  }
  const schedule = (fn, delay, repeat) => { const id = ++timerId; timers.set(id, { fn, at: now + delay, repeat }); return id; };
  async function advance(ms) {
    const target = now + ms;
    for (let i = 0; i < 1000; i++) {
      const next = [...timers.entries()].filter(([, value]) => value.at <= target).sort((a, b) => a[1].at - b[1].at)[0];
      if (!next) { now = target; await tick(); return; }
      const [id, timer] = next; now = timer.at;
      if (timer.repeat) timer.at += timer.repeat; else timers.delete(id);
      timer.fn(); await tick();
    }
    throw new Error('Too many fake timer iterations');
  }
  class Element {
    constructor() { this.value = ''; this.textContent = ''; this.disabled = false; this.events = {}; this.children = []; this.paused = true; this.muted = false; this.volume = 1; }
    addEventListener(name, handler) { this.events[name] = handler; }
    async click() { return this.events.click?.(); }
    append(...children) { this.children.push(...children); }
    replaceChildren(...children) { this.children = children; }
    play() { this.paused = false; return Promise.resolve(); }
  }
  for (const id of ['token','profile','drug','date','start','stop','calendar','status','audio','conversation','progress','result']) elements.set(id, new Element());
  elements.get('profile').value = '13'; elements.get('drug').value = '25';
  const track = { enabled: true, stop() { stops++; } };
  const stream = { getTracks: () => [track], getAudioTracks: () => [track] };
  class Peer {
    constructor() { this.iceGatheringState = 'complete'; this.events = {}; peers.push(this); }
    addEventListener(name, handler) { this.events[name] = handler; }
    removeEventListener() {}
    addTrack() {}
    createDataChannel() { this.channel = new Element(); this.channel.close = () => {}; return this.channel; }
    async createOffer() { return { type: 'offer', sdp: 'fixture-offer' }; }
    async setLocalDescription(offer) { this.localDescription = offer; }
    async setRemoteDescription(answer) { this.answer = answer; }
    close() { this.closed = true; }
  }
  class FakeAudioContext {
    constructor() { this.state = 'running'; this.destination = {}; }
    createMediaStreamSource() { return { connect() {}, disconnect() {} }; }
    createAnalyser() { return { fftSize: 2048, connect() {}, getFloatTimeDomainData(samples) { samples.fill(amplitude); } }; }
    createGain() { return { gain: { value: 1 }, connect() {} }; }
    resume() { return Promise.resolve(); }
    close() { audioClosed++; this.state = 'closed'; return Promise.resolve(); }
  }
  const globals = {
    document: { getElementById: id => elements.get(id), createElement: () => new Element(), createTextNode: text => text },
    isSecureContext: true, RTCPeerConnection: Peer, MediaStream: class {}, Intl, Date: TestDate, console,
    ...(audioSupported ? { AudioContext: FakeAudioContext } : {}),
    navigator: { mediaDevices: { getUserMedia: () => media ? media.promise : Promise.resolve(stream) } },
    setInterval: (fn, delay) => schedule(fn, delay, delay), clearInterval: id => timers.delete(id),
    setTimeout: (fn, delay) => schedule(fn, delay, 0), clearTimeout: id => timers.delete(id), addEventListener() {},
    localStorage: { setItem() { throw new Error('Tokens must not be stored'); } },
    fetch: async (url, options = {}) => {
      requests.push({ url, options });
      if (fetcher) return fetcher(url, options);
      if (options.method === 'POST') return response({ sessionId: 'fixture-session', sdp: 'fixture-answer', expiresAt: '2099-01-01T00:00:00Z' }, 201);
      if (options.method === 'DELETE') return response(null, 204);
      return response({ status: 'LISTENING' });
    }
  };
  globals.window = globals;
  vm.runInNewContext(source, globals, { filename: 'medication-live/live.js' });
  return { el: id => elements.get(id), stream, requests, peers, stopped: () => stops, advance, jump: ms => { now += ms; },
    sound: value => { amplitude = value; }, audioClosed: () => audioClosed,
    emitTrack: () => peers.at(-1).events.track({ track: {} }),
    receive: (type, delta) => peers.at(-1).channel.events.message({ data: JSON.stringify({ type, delta }) }) };
}

test('로그인 토큰이 없으면 마이크·API를 시작하지 않음', async () => {
  const app = environment();
  await app.el('start').click();
  assert.match(app.el('status').textContent, /로그인 토큰/);
  assert.equal(app.requests.length, 0);
});

test('내 약 ID를 비워두면 null로 Live를 시작할 수 있음', async () => {
  const app = environment();
  app.el('token').value = 'fixture-token';
  app.el('drug').value = '';
  await app.el('start').click();
  const start = app.requests.find(request => request.options.method === 'POST');
  assert.ok(start);
  assert.equal(JSON.parse(start.options.body).selectedMyDrugId, null);
  await app.el('stop').click();
});

test('내 약 ID는 생략 가능해도 0이나 음수는 허용하지 않음', async () => {
  for (const id of ['0', '-1', 'NaN']) {
    const app = environment();
    app.el('token').value = 'fixture-token';
    app.el('drug').value = id;
    await app.el('start').click();
    assert.equal(app.requests.length, 0);
    assert.equal(app.peers.length, 0);
  }
});

test('1분 만료 즉시 시간 초과 안내와 미디어 정리를 하고 종료 응답을 기다림', async () => {
  const closing = deferred();
  const app = environment({ fetcher: (url, options) => {
    if (options.method === 'POST') return response({ sessionId: 'fixture-session', sdp: 'answer', expiresAt: '2026-10-19T00:01:00Z' }, 201);
    if (options.method === 'DELETE') return closing.promise;
    return response({ status: 'LISTENING' });
  } });
  app.el('token').value = 'fixture-jwt'; await app.el('start').click();
  await app.advance(59999); assert.equal(app.stopped(), 0);
  await app.advance(1);
  assert.equal(app.el('status').textContent, '시간이 초과되어 종료됩니다');
  assert.equal(app.stopped(), 1); assert.equal(app.peers[0].closed, true);
  assert.equal(app.requests.filter(request => request.options.method === 'DELETE').length, 1);
  closing.resolve(response({ message: '이미 종료된 세션' }, 404)); await tick();
  assert.equal(app.el('status').textContent, '시간이 초과되어 종료됩니다');
  assert.equal(app.el('start').disabled, false);
});

test('타이머보다 서버 종료 이벤트가 먼저 와도 만료 시각이면 시간 초과로 안내함', async () => {
  const app = environment({ fetcher: (url, options) => {
    if (options.method === 'POST') return response({ sessionId: 'fixture-session', sdp: 'answer', expiresAt: '2026-10-19T00:01:00Z' }, 201);
    if (options.method === 'DELETE') return response(null, 204);
    return response({ status: 'LISTENING' });
  } });
  app.el('token').value = 'fixture-jwt'; await app.el('start').click();
  app.jump(60000); app.receive('session.closed'); await tick();
  assert.equal(app.el('status').textContent, '시간이 초과되어 종료됩니다');
  assert.equal(app.stopped(), 1); assert.equal(app.el('start').disabled, false);
});

test('마이크 권한을 기다리는 중 종료하면 늦게 도착한 트랙도 정리함', async () => {
  const media = deferred(), app = environment({ media });
  app.el('token').value = 'fixture-jwt';
  const pending = app.el('start').click();
  await app.el('stop').click();
  media.resolve(app.stream); await pending;
  assert.equal(app.stopped(), 1);
  assert.equal(app.requests.length, 0);
  assert.equal(app.el('start').disabled, false);
});

test('연결 생성 중 종료하면 늦게 생성된 원격 세션을 같은 JWT로 닫음', async () => {
  const creation = deferred();
  const app = environment({ fetcher: (url, options) => options.method === 'POST' ? creation.promise : response(null, 204) });
  app.el('token').value = 'fixture-jwt';
  const pending = app.el('start').click(); await tick();
  assert.equal(app.requests[0].options.method, 'POST');
  await app.el('stop').click();
  creation.resolve(response({ sessionId: 'late-session', sdp: 'answer', expiresAt: '2099-01-01T00:00:00Z' }, 201));
  await pending;
  const closing = app.requests.find(request => request.options.method === 'DELETE');
  assert.ok(closing.url.endsWith('/late-session'));
  assert.equal(closing.options.headers.Authorization, 'Bearer fixture-jwt');
  assert.equal(app.stopped(), 1);
});

test('API 오류를 자동 재시도하지 않고 마이크를 정리함', async () => {
  const app = environment({ fetcher: () => response({ message: 'Live 설정이 없습니다.' }, 503) });
  app.el('token').value = 'fixture-jwt';
  await app.el('start').click();
  assert.equal(app.requests.length, 1);
  assert.equal(app.stopped(), 1);
  assert.match(app.el('status').textContent, /Live 설정/);
});

test('연결 성공 후 SDP를 적용하고 종료 시 서버·미디어를 정리함', async () => {
  const app = environment();
  app.el('token').value = 'Bearer fixture-jwt';
  await app.el('start').click();
  assert.equal(app.peers[0].answer.sdp, 'fixture-answer');
  assert.equal(app.requests[0].options.headers.Authorization, 'Bearer fixture-jwt');
  assert.equal(JSON.parse(app.requests[0].options.body).selectedMyDrugId, 25);
  await app.el('stop').click();
  await tick();
  assert.ok(app.requests.some(request => request.options.method === 'DELETE'));
  assert.equal(app.stopped(), 1);
  assert.equal(app.peers[0].closed, true);
});

function completionEnvironment({ audioSupported = true, calendarFails = false, alreadyClosed = false, operation = 'SAVE' } = {}) {
  let saved = false;
  const app = environment({ audioSupported, fetcher: (url, options) => {
    if (options.method === 'POST') return response({ sessionId: 'fixture-session', sdp: 'fixture-answer', expiresAt: '2099-01-01T00:00:00Z' }, 201);
    if (options.method === 'DELETE') return alreadyClosed ? response({ message: '이미 종료된 세션' }, 404) : response(null, 204);
    if (url.startsWith('/api/calendar')) return calendarFails ? response({ message: '캘린더 오류' }, 503) : response([]);
    return response(saved ? { status: operation === 'DELETE' ? 'DELETED' : operation === 'STOP' ? 'STOPPED' : 'SAVED',
      savedSchedule: operation === 'DELETE' ? null : { myDrugId: 25, startDate: '2026-10-19' },
      ...(operation === 'SAVE' ? {} : { completedOperation: { action: operation, profileId: 14, myDrugId: 27 } }) } : { status: 'LISTENING' });
  } });
  app.markSaved = () => { saved = true; };
  app.closingRequests = () => app.requests.filter(request => request.options.method === 'DELETE');
  app.el('token').value = 'fixture-jwt';
  return app;
}

test('저장 성공 뒤 완료 음성을 들은 후 무음이면 자동 종료하고 마이크를 해제함', async () => {
  const app = completionEnvironment();
  await app.el('start').click(); app.emitTrack();
  app.markSaved(); await app.advance(1500);
  assert.equal(app.stream.getAudioTracks()[0].enabled, false);
  assert.equal(app.closingRequests().length, 0);
  app.receive('session.output_transcript.delta', '등록');
  app.receive('session.output_transcript.delta', '했어요.');
  app.sound(0.1); await app.advance(300);
  app.sound(0); await app.advance(600);
  assert.equal(app.closingRequests().length, 0);
  await app.advance(200);
  assert.equal(app.closingRequests().length, 1);
  assert.equal(app.stopped(), 1);
  assert.equal(app.audioClosed(), 1);
  assert.equal(app.el('start').disabled, false);
  assert.match(app.el('status').textContent, /등록.*자동 종료/);
});

test('완료 자막이 끊겨도 실제 음성이 계속 재생되면 바로 종료하지 않음', async () => {
  const app = completionEnvironment();
  await app.el('start').click(); app.emitTrack();
  app.markSaved(); await app.advance(1500);
  app.receive('session.output_transcript.delta', '등록했어요.');
  app.sound(0.1); await app.advance(2000);
  assert.equal(app.closingRequests().length, 0);
  app.sound(0); await app.advance(800);
  assert.equal(app.closingRequests().length, 1);
});

test('저장 성공이 없으면 완료처럼 들리는 자막과 무음만으로 종료하지 않음', async () => {
  const app = completionEnvironment();
  await app.el('start').click(); app.emitTrack();
  app.receive('session.output_transcript.delta', '등록했어요.');
  app.sound(0.1); await app.advance(300);
  app.sound(0); await app.advance(1000);
  assert.equal(app.closingRequests().length, 0);
  assert.equal(app.stream.getAudioTracks()[0].enabled, true);
  await app.el('stop').click(); await tick();
});

test('오디오 감지를 지원하지 않아도 저장 후 8초 안에 자동 정리함', async () => {
  const app = completionEnvironment({ audioSupported: false });
  await app.el('start').click(); app.emitTrack();
  app.markSaved(); await app.advance(1500);
  await app.advance(7900);
  assert.equal(app.closingRequests().length, 0);
  await app.advance(100);
  assert.equal(app.closingRequests().length, 1);
  assert.equal(app.stopped(), 1);
});

test('재생이 일시 정지됐으면 들었다고 판단하지 않고 유예 시간 뒤 정리함', async () => {
  const app = completionEnvironment();
  await app.el('start').click(); app.emitTrack();
  app.markSaved(); await app.advance(1500);
  app.el('audio').paused = true;
  app.receive('session.output_transcript.delta', '등록했어요.');
  app.sound(0.1); await app.advance(300);
  app.sound(0); await app.advance(1000);
  assert.equal(app.closingRequests().length, 0);
  await app.advance(6700);
  assert.equal(app.closingRequests().length, 1);
});

test('캘린더 조회 실패는 저장 성공을 뒤집거나 완료 음성을 즉시 끊지 않음', async () => {
  const app = completionEnvironment({ audioSupported: false, calendarFails: true, alreadyClosed: true });
  await app.el('start').click();
  app.markSaved(); await app.advance(1500);
  assert.match(app.el('result').textContent, /일정 등록은 완료.*캘린더 조회만 실패/);
  assert.equal(app.closingRequests().length, 0);
  await app.advance(8000);
  assert.equal(app.closingRequests().length, 1);
  assert.match(app.el('status').textContent, /등록.*자동 종료/);
  assert.doesNotMatch(app.el('status').textContent, /이미 종료된 세션/);
});

test('완료 안내 대기 중 수동 종료하면 남은 타이머가 다시 종료 요청을 보내지 않음', async () => {
  const app = completionEnvironment();
  await app.el('start').click(); app.emitTrack();
  app.markSaved(); await app.advance(1500);
  await app.el('stop').click(); await tick();
  assert.equal(app.closingRequests().length, 1);
  await app.advance(10000);
  assert.equal(app.closingRequests().length, 1);
  assert.equal(app.stopped(), 1);
  assert.equal(app.audioClosed(), 1);
});

test('일정 삭제는 savedSchedule이 없어도 캘린더를 갱신하고 완료 음성 뒤 종료함', async () => {
  const app = completionEnvironment({ operation: 'DELETE' });
  await app.el('start').click(); app.emitTrack();
  app.markSaved(); await app.advance(1500);
  assert.ok(app.requests.some(request => request.url.startsWith('/api/calendar')));
  app.receive('session.output_transcript.delta', '일정을 삭제했어요.');
  app.sound(0.1); await app.advance(300);
  app.sound(0); await app.advance(800);
  assert.equal(app.closingRequests().length, 1);
  assert.match(app.el('status').textContent, /삭제.*자동 종료/);
  assert.doesNotMatch(app.el('status').textContent, /등록/);
});

test('그만 먹기는 등록 완료가 아닌 오늘까지 변경 완료로 안내하고 종료함', async () => {
  const app = completionEnvironment({ operation: 'STOP' });
  await app.el('start').click(); app.emitTrack();
  app.markSaved(); await app.advance(1500);
  app.receive('session.output_transcript.delta', '오늘까지만 복용하도록 변경했어요.');
  app.sound(0.1); await app.advance(300);
  app.sound(0); await app.advance(800);
  assert.equal(app.closingRequests().length, 1);
  assert.match(app.el('status').textContent, /오늘까지로 변경.*자동 종료/);
});
