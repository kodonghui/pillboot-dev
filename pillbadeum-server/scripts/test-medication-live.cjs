const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../src/main/resources/medication-live/live.js'), 'utf8');
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; };
const response = (body, status = 200) => ({ status, ok: status >= 200 && status < 300, json: async () => body });
const tick = () => new Promise(resolve => setImmediate(resolve));

function environment({ media, fetcher } = {}) {
  const elements = new Map(), requests = [], peers = [];
  let stops = 0;
  class Element {
    constructor() { this.value = ''; this.textContent = ''; this.disabled = false; this.events = {}; this.children = []; }
    addEventListener(name, handler) { this.events[name] = handler; }
    async click() { return this.events.click?.(); }
    append(...children) { this.children.push(...children); }
    replaceChildren(...children) { this.children = children; }
    play() { return Promise.resolve(); }
  }
  for (const id of ['token','profile','drug','date','start','stop','calendar','status','audio','conversation','progress','result']) elements.set(id, new Element());
  elements.get('profile').value = '13'; elements.get('drug').value = '25';
  const track = { stop() { stops++; } };
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
  const globals = {
    document: { getElementById: id => elements.get(id), createElement: () => new Element(), createTextNode: text => text },
    isSecureContext: true, RTCPeerConnection: Peer, MediaStream: class {}, Intl, Date, console,
    navigator: { mediaDevices: { getUserMedia: () => media ? media.promise : Promise.resolve(stream) } },
    setInterval: () => 1, clearInterval() {}, setTimeout: () => 1, clearTimeout() {}, addEventListener() {},
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
  return { el: id => elements.get(id), stream, requests, peers, stopped: () => stops };
}

test('로그인 토큰이 없으면 마이크·API를 시작하지 않음', async () => {
  const app = environment();
  await app.el('start').click();
  assert.match(app.el('status').textContent, /로그인 토큰/);
  assert.equal(app.requests.length, 0);
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
