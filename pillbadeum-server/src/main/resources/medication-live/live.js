"use strict";
(() => {
  const el = (id) => document.getElementById(id);
  const status = (message) => { el("status").textContent = message; };
  let peer, microphone, channel, sessionId, polling, expiry, busy = false, stopping = false;
  let activeToken = "", activeDate = "", lastSaved = "", currentSpeaker = "", transcriptText, generation = 0, checking = null;
  let completing = false, completionTimer, playbackTimer, playbackContext, playbackSource, playbackAnalyser, playbackSamples;
  let completionText = "", completionCaptionAt = 0, completionAudioHeard = false, lastAudibleAt = 0, lastOutputCaptionAt = 0;
  const completedMessage = "일정을 등록하고 대화를 자동 종료했어요.";
  el("date").value = new Intl.DateTimeFormat("sv-SE", { timeZone: "Asia/Seoul", year: "numeric", month: "2-digit", day: "2-digit" }).format(new Date());

  function token() { return el("token").value.trim().replace(/^Bearer\s+/i, ""); }
  async function api(path, options = {}) {
    const response = await fetch(path, { ...options, headers: { "Content-Type": "application/json", Authorization: "Bearer " + (activeToken || token()), ...options.headers } });
    if (!response.ok) {
      const error = await response.json().catch(() => ({}));
      const failure = new Error(error.message || "요청에 실패했어요. HTTP " + response.status);
      failure.status = response.status; throw failure;
    }
    return response.status === 204 ? null : response.json();
  }
  function setBusy(value) {
    busy = value;
    el("start").disabled = value;
    el("stop").disabled = !value;
    ["token", "profile", "drug", "date"].forEach((id) => { el(id).disabled = value; });
  }
  function releaseMedia() {
    clearInterval(polling); clearTimeout(expiry); clearTimeout(completionTimer); clearInterval(playbackTimer);
    microphone?.getTracks().forEach((track) => track.stop());
    channel?.close(); peer?.close(); el("audio").srcObject = null;
    playbackSource?.disconnect();
    if (playbackContext) void playbackContext.close().catch(() => {});
    playbackContext = playbackSource = playbackAnalyser = playbackSamples = null;
    peer = microphone = channel = null;
  }
  function cleanup() {
    releaseMedia(); completing = false;
    sessionId = null; stopping = false; activeToken = ""; checking = null; setBusy(false);
  }
  async function finish(message = "대화를 종료했어요. 이미 저장한 일정은 유지돼요.") {
    if (stopping) return;
    stopping = true; generation++; el("stop").disabled = true;
    const id = sessionId;
    releaseMedia();
    try {
      if (id) await api("/api/medication/live/sessions/" + encodeURIComponent(id), { method: "DELETE" });
      status(message);
    } catch (error) {
      status(error.status === 404 ? message : error.message + " 연결 종료는 서버의 시간 제한에서도 처리돼요.");
    }
    finally { cleanup(); }
  }
  function watchPlayback(stream) {
    const AudioContext = window.AudioContext || window.webkitAudioContext;
    if (!AudioContext) return;
    clearInterval(playbackTimer); playbackSource?.disconnect();
    if (playbackContext) void playbackContext.close().catch(() => {});
    try {
      playbackContext = new AudioContext();
      playbackSource = playbackContext.createMediaStreamSource(stream);
      playbackAnalyser = playbackContext.createAnalyser(); playbackAnalyser.fftSize = 2048;
      playbackSamples = new Float32Array(playbackAnalyser.fftSize);
      const silentOutput = playbackContext.createGain(); silentOutput.gain.value = 0;
      playbackSource.connect(playbackAnalyser); playbackAnalyser.connect(silentOutput); silentOutput.connect(playbackContext.destination);
      void playbackContext.resume().catch(() => {});
      playbackTimer = setInterval(() => {
        const audio = el("audio");
        if (stopping || playbackContext?.state !== "running" || audio.paused || audio.muted || audio.volume === 0) return;
        playbackAnalyser.getFloatTimeDomainData(playbackSamples);
        const rms = Math.sqrt(playbackSamples.reduce((sum, sample) => sum + sample * sample, 0) / playbackSamples.length);
        const now = Date.now();
        if (rms > 0.008) {
          lastAudibleAt = now;
          if (completionCaptionAt) completionAudioHeard = true;
        }
        // 자막 간격만으로 종료하지 않는다. 완료 문구와 실제 오디오 활동 이후의 무음을 함께 본다.
        if (completing && completionAudioHeard && now - lastAudibleAt >= 700 && now - lastOutputCaptionAt >= 700) {
          void finish(completedMessage);
        }
      }, 100);
    } catch {
      if (playbackContext) void playbackContext.close().catch(() => {});
      playbackContext = playbackSource = playbackAnalyser = playbackSamples = null;
    }
  }
  function completeAfterSpeech() {
    if (completing) return;
    completing = true;
    microphone?.getAudioTracks().forEach((track) => { track.enabled = false; });
    status("등록됐어요. 완료 안내 후 대화를 자동 종료해요.");
    // 오디오 감지를 지원하지 않거나 재생이 막힌 경우에도 세션을 계속 남기지 않는다.
    completionTimer = setTimeout(() => { void finish(completedMessage); }, 8000);
  }
  function caption(role, delta) {
    if (currentSpeaker !== role || !transcriptText) {
      const row = document.createElement("div"); row.className = "message";
      const label = document.createElement("span"); label.className = "speaker";
      label.textContent = role === "user" ? "나: " : "Live: ";
      transcriptText = document.createElement("span"); row.append(label, transcriptText);
      el("conversation").append(row); currentSpeaker = role;
    }
    transcriptText.append(document.createTextNode(delta));
    el("conversation").scrollTop = el("conversation").scrollHeight;
  }
  async function calendar() {
    const date = busy ? activeDate : el("date").value;
    const result = await api("/api/calendar?from=" + encodeURIComponent(date) + "&to=" + encodeURIComponent(date));
    el("result").textContent = JSON.stringify(result, null, 2);
  }
  async function progress() {
    if (!sessionId || stopping || checking === sessionId) return;
    const id = sessionId;
    checking = id;
    try {
      const result = await api("/api/medication/live/sessions/" + encodeURIComponent(id));
      if (sessionId !== id) return;
      el("progress").textContent = JSON.stringify(result, null, 2);
      if (result.savedSchedule) {
        completeAfterSpeech();
        const key = JSON.stringify(result.savedSchedule);
        if (key !== lastSaved) {
          lastSaved = key;
          await calendar().catch((error) => {
            if (sessionId === id) el("result").textContent = "일정은 등록됐어요. 캘린더 조회만 실패했어요: " + error.message;
          });
        }
      }
    } catch (error) { if (sessionId === id) await finish(completing ? completedMessage : error.message); }
    finally { if (checking === id) checking = null; }
  }
  async function ice(connection) {
    if (connection.iceGatheringState === "complete") return;
    await new Promise((resolve, reject) => {
      const timeout = setTimeout(() => { connection.removeEventListener("icegatheringstatechange", changed); reject(new Error("음성 연결 준비 시간이 초과됐어요.")); }, 10000);
      function changed() {
        if (connection.iceGatheringState !== "complete") return;
        clearTimeout(timeout); connection.removeEventListener("icegatheringstatechange", changed); resolve();
      }
      connection.addEventListener("icegatheringstatechange", changed); changed();
    });
  }
  el("start").addEventListener("click", async () => {
    if (busy) return;
    if (!token()) { status("로그인 토큰을 먼저 넣어주세요."); return; }
    const selectedProfileId = Number(el("profile").value), selectedMyDrugId = Number(el("drug").value);
    if (!Number.isSafeInteger(selectedProfileId) || selectedProfileId <= 0 || !Number.isSafeInteger(selectedMyDrugId) || selectedMyDrugId <= 0 || !el("date").value) {
      status("실제 프로필·약 ID와 선택 날짜를 넣어주세요."); return;
    }
    const attempt = ++generation, connectionToken = token();
    const alive = () => generation === attempt && busy && !stopping;
    setBusy(true); activeToken = connectionToken; activeDate = el("date").value; lastSaved = "";
    completing = false; completionText = ""; completionCaptionAt = lastAudibleAt = lastOutputCaptionAt = 0; completionAudioHeard = false;
    el("conversation").replaceChildren(); transcriptText = null; currentSpeaker = "";
    status("마이크와 Live 연결을 준비하고 있어요.");
    try {
      if (!window.isSecureContext || !navigator.mediaDevices?.getUserMedia || !window.RTCPeerConnection) throw new Error("localhost 또는 HTTPS의 마이크 지원 브라우저에서 열어주세요.");
      const connection = new RTCPeerConnection(); peer = connection;
      connection.addEventListener("track", (event) => {
        if (!alive()) return;
        const output = new MediaStream([event.track]);
        el("audio").srcObject = output;
        watchPlayback(output);
        el("audio").play().catch(() => { if (alive()) status("아래 오디오의 재생 버튼을 눌러 음성을 들어주세요."); });
      });
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      if (!alive()) { stream.getTracks().forEach((track) => track.stop()); connection.close(); return; }
      microphone = stream;
      microphone.getAudioTracks().forEach((track) => connection.addTrack(track, microphone));
      channel = connection.createDataChannel("oai-events");
      channel.addEventListener("message", ({ data }) => {
        if (!alive()) return;
        let event; try { event = JSON.parse(data); } catch { return; }
        if (event.type === "session.started") status("연결됐어요. 등록할 약 일정을 말해보세요.");
        else if (event.type === "session.input_transcript.delta") {
          if (!completing) { completionText = ""; completionCaptionAt = 0; completionAudioHeard = false; }
          caption("user", event.delta || "");
        } else if (event.type === "session.output_transcript.delta") {
          caption("assistant", event.delta || ""); lastOutputCaptionAt = Date.now();
          completionText = (completionText + (event.delta || "")).slice(-160);
          if (!completionCaptionAt && /(등록|저장).{0,24}(했|됐|되었|완료)|완료(됐|했|되었|예요|입니다)/.test(completionText)) {
            completionCaptionAt = Date.now();
          }
        } else if (event.type === "session.closed") void finish(completing ? completedMessage : "Live 대화가 끝났어요.");
        else if (event.type === "error") status("Live에서 오류가 발생했어요. 대화를 종료하고 다시 연결해주세요.");
      });
      connection.addEventListener("connectionstatechange", () => {
        if (alive() && ["failed", "disconnected"].includes(connection.connectionState)) void finish(completing ? completedMessage : "음성 연결이 끊겨 대화를 종료했어요.");
      });
      await connection.setLocalDescription(await connection.createOffer()); await ice(connection);
      if (!alive()) { connection.close(); return; }
      const result = await api("/api/medication/live/sessions", { method: "POST", body: JSON.stringify({ sdp: connection.localDescription.sdp,
        selectedProfileId, selectedMyDrugId, selectedDate: activeDate }) });
      if (!alive()) {
        await api("/api/medication/live/sessions/" + encodeURIComponent(result.sessionId), {
          method: "DELETE", headers: { Authorization: "Bearer " + connectionToken }
        }).catch(() => {});
        connection.close(); return;
      }
      sessionId = result.sessionId;
      await connection.setRemoteDescription({ type: "answer", sdp: result.sdp });
      if (!alive()) { connection.close(); return; }
      polling = setInterval(() => { void progress(); }, 1500);
      expiry = setTimeout(() => { void finish(completing ? completedMessage : "대화 시간 제한으로 종료했어요."); }, Math.max(0, Date.parse(result.expiresAt) - Date.now()));
      await progress();
    } catch (error) { if (alive()) await finish(error.message || "Live 연결에 실패했어요."); }
  });
  el("stop").addEventListener("click", () => { void finish(); });
  el("calendar").addEventListener("click", () => { void calendar().catch((error) => status(error.message)); });
  window.addEventListener("pagehide", () => {
    if (sessionId) fetch("/api/medication/live/sessions/" + encodeURIComponent(sessionId), { method: "DELETE", keepalive: true,
      headers: { Authorization: "Bearer " + activeToken } }).catch(() => {});
    releaseMedia();
  });
})();
