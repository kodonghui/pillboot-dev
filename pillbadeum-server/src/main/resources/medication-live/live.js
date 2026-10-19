"use strict";
(() => {
  const el = (id) => document.getElementById(id);
  const status = (message) => { el("status").textContent = message; };
  let peer, microphone, channel, sessionId, polling, expiry, busy = false, stopping = false;
  let activeToken = "", activeDate = "", lastSaved = "", currentSpeaker = "", transcriptText, generation = 0, checking = false;
  el("date").value = new Intl.DateTimeFormat("sv-SE", { timeZone: "Asia/Seoul", year: "numeric", month: "2-digit", day: "2-digit" }).format(new Date());

  function token() { return el("token").value.trim().replace(/^Bearer\s+/i, ""); }
  async function api(path, options = {}) {
    const response = await fetch(path, { ...options, headers: { "Content-Type": "application/json", Authorization: "Bearer " + (activeToken || token()), ...options.headers } });
    if (!response.ok) {
      const error = await response.json().catch(() => ({}));
      throw new Error(error.message || "요청에 실패했어요. HTTP " + response.status);
    }
    return response.status === 204 ? null : response.json();
  }
  function setBusy(value) {
    busy = value;
    el("start").disabled = value;
    el("stop").disabled = !value;
    ["token", "profile", "drug", "date"].forEach((id) => { el(id).disabled = value; });
  }
  function cleanup() {
    clearInterval(polling); clearTimeout(expiry);
    microphone?.getTracks().forEach((track) => track.stop());
    channel?.close(); peer?.close(); el("audio").srcObject = null;
    peer = microphone = channel = null;
    sessionId = null; stopping = false; activeToken = ""; setBusy(false);
  }
  async function finish(message = "대화를 종료했어요. 이미 저장한 일정은 유지돼요.") {
    if (stopping) return;
    stopping = true; generation++; el("stop").disabled = true;
    const id = sessionId;
    try {
      if (id) await api("/api/medication/live/sessions/" + encodeURIComponent(id), { method: "DELETE" });
      status(message);
    } catch (error) { status(error.message + " 연결 종료는 서버의 시간 제한에서도 처리돼요."); }
    finally { cleanup(); }
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
    if (!sessionId || stopping || checking) return;
    const id = sessionId;
    checking = true;
    try {
      const result = await api("/api/medication/live/sessions/" + encodeURIComponent(id));
      if (sessionId !== id) return;
      el("progress").textContent = JSON.stringify(result, null, 2);
      if (result.savedSchedule) {
        const key = JSON.stringify(result.savedSchedule);
        if (key !== lastSaved) { lastSaved = key; await calendar(); }
      }
    } catch (error) { if (sessionId === id) await finish(error.message); }
    finally { checking = false; }
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
    el("conversation").replaceChildren(); transcriptText = null; currentSpeaker = "";
    status("마이크와 Live 연결을 준비하고 있어요.");
    try {
      if (!window.isSecureContext || !navigator.mediaDevices?.getUserMedia || !window.RTCPeerConnection) throw new Error("localhost 또는 HTTPS의 마이크 지원 브라우저에서 열어주세요.");
      const connection = new RTCPeerConnection(); peer = connection;
      connection.addEventListener("track", (event) => {
        el("audio").srcObject = new MediaStream([event.track]);
        el("audio").play().catch(() => status("아래 오디오의 재생 버튼을 눌러 음성을 들어주세요."));
      });
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      if (!alive()) { stream.getTracks().forEach((track) => track.stop()); connection.close(); return; }
      microphone = stream;
      microphone.getAudioTracks().forEach((track) => connection.addTrack(track, microphone));
      channel = connection.createDataChannel("oai-events");
      channel.addEventListener("message", ({ data }) => {
        let event; try { event = JSON.parse(data); } catch { return; }
        if (event.type === "session.started") status("연결됐어요. 등록할 약 일정을 말해보세요.");
        else if (event.type === "session.input_transcript.delta") caption("user", event.delta || "");
        else if (event.type === "session.output_transcript.delta") caption("assistant", event.delta || "");
        else if (event.type === "session.closed") void finish("Live 대화가 끝났어요.");
        else if (event.type === "error") status("Live에서 오류가 발생했어요. 대화를 종료하고 다시 연결해주세요.");
      });
      connection.addEventListener("connectionstatechange", () => {
        if (!stopping && ["failed", "disconnected"].includes(connection.connectionState)) void finish("음성 연결이 끊겨 대화를 종료했어요.");
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
      expiry = setTimeout(() => { void finish("대화 시간 제한으로 종료했어요."); }, Math.max(0, Date.parse(result.expiresAt) - Date.now()));
      await progress();
    } catch (error) { if (alive()) await finish(error.message || "Live 연결에 실패했어요."); }
  });
  el("stop").addEventListener("click", () => { void finish(); });
  el("calendar").addEventListener("click", () => { void calendar().catch((error) => status(error.message)); });
  window.addEventListener("pagehide", () => {
    if (sessionId) fetch("/api/medication/live/sessions/" + encodeURIComponent(sessionId), { method: "DELETE", keepalive: true,
      headers: { Authorization: "Bearer " + activeToken } }).catch(() => {});
    microphone?.getTracks().forEach((track) => track.stop()); peer?.close();
  });
})();
