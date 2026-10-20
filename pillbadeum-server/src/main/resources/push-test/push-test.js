(() => {
  const el = (id) => document.getElementById(id);
  const log = (message) => { el("log").textContent = message; };
  const token = () => el("token").value.trim().replace(/^Bearer\s+/i, "");

  async function api(path, options = {}) {
    const response = await fetch(path, {
      ...options,
      headers: { "Content-Type": "application/json", Authorization: "Bearer " + token(), ...options.headers }
    });
    const text = await response.text();
    const body = text ? JSON.parse(text) : null;
    if (!response.ok) throw new Error(`${response.status} ${body?.code || ""} ${body?.message || ""}`.trim());
    return body;
  }

  // VAPID 공개 키(base64url)를 브라우저가 받는 바이트 배열로 바꾼다.
  function keyBytes(base64url) {
    const base64 = base64url.replace(/-/g, "+").replace(/_/g, "/").padEnd(Math.ceil(base64url.length / 4) * 4, "=");
    return Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));
  }

  el("login").addEventListener("click", async () => {
    try {
      const response = await fetch("/api/auth/login", {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email: el("email").value.trim(), password: el("password").value })
      });
      const body = await response.json();
      if (!response.ok) throw new Error(body.message || response.status);
      el("token").value = body.accessToken;
      el("password").value = "";
      log("로그인했어요. 2번 '알림 켜기'를 눌러주세요.");
    } catch (e) { log("로그인 실패: " + e.message); }
  });

  el("enable").addEventListener("click", async () => {
    try {
      if (!token()) throw new Error("먼저 로그인하거나 토큰을 넣어주세요.");
      if (!("serviceWorker" in navigator) || !("PushManager" in window)) throw new Error("이 브라우저는 웹 푸시를 지원하지 않아요. 크롬에서 열어주세요.");
      log("서비스 워커 등록 중...");
      const registration = await navigator.serviceWorker.register("/dev/push-sw.js");
      await navigator.serviceWorker.ready;

      log("브라우저 알림 허용을 기다리는 중...");
      const permission = await Notification.requestPermission();
      if (permission !== "granted") throw new Error("알림이 차단돼 있어요. 주소창 왼쪽 사이트 설정에서 알림을 '허용'으로 바꿔주세요.");

      const { publicKey } = await api("/api/push/vapid-public-key");
      let subscription = await registration.pushManager.getSubscription();
      if (subscription) {
        // 서버 키가 바뀌었을 수 있으니 예전 구독은 지우고 새로 만든다.
        await subscription.unsubscribe();
      }
      subscription = await registration.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: keyBytes(publicKey) });

      const json = subscription.toJSON();
      const saved = await api("/api/push/subscriptions", {
        method: "POST", body: JSON.stringify({ endpoint: json.endpoint, keys: json.keys, userAgent: navigator.userAgent })
      });

      const settings = await api("/api/notification-settings");
      if (!settings.pushEnabled || !settings.consentActive) {
        if (!confirm(`복약 알림 수신(약관 ${settings.consentPolicyVersion})에 동의하고 알림을 켤까요?`)) {
          throw new Error("동의하지 않아 알림은 꺼진 채로 두었어요.");
        }
        await api("/api/notification-settings", {
          method: "PUT",
          body: JSON.stringify({ pushEnabled: true, notificationConsent: { agreed: true, policyVersion: settings.consentPolicyVersion } })
        });
      }
      log(`알림이 켜졌어요. (구독 번호 ${saved.subscriptionId})\n3번 '시험 알림 보내기'를 눌러보세요.`);
    } catch (e) { log("알림 켜기 실패: " + e.message); }
  });

  el("send").addEventListener("click", async () => {
    try {
      const result = await api("/dev/push-test/send", { method: "POST" });
      log(`보냄: 성공 ${result.sent} · 실패 ${result.failed} · 지운 구독 ${result.removed} (구독 ${result.subscriptions}개)` +
          (result.subscriptions === 0 ? "\n구독이 없어요. 2번을 먼저 눌러주세요." : "\n화면 오른쪽 아래를 확인하세요."));
    } catch (e) { log("보내기 실패: " + e.message); }
  });
})();
