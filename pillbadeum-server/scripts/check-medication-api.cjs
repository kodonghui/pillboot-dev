"use strict";
// 실행 중인 서버의 읽기 전용 검증. 토큰·응답 원문은 출력하지 않는다.
const assert = require("node:assert/strict");
const base = new URL(process.env.MEDICATION_BASE_URL || "http://localhost:8080");
const token = (process.env.MEDICATION_ACCESS_TOKEN || "").trim().replace(/^Bearer\s+/i, "");
const today = new Intl.DateTimeFormat("sv-SE", { timeZone: "Asia/Seoul", year: "numeric", month: "2-digit", day: "2-digit" }).format(new Date());
let passed = 0, failed = 0, skipped = 0, selected;

async function request(path, { auth = true, method = "GET", body, origin, invalidToken = false } = {}) {
  const headers = {};
  if (auth && token) headers.Authorization = "Bearer " + (invalidToken ? "invalid-test-token" : token);
  if (body !== undefined) headers["Content-Type"] = "application/json";
  if (origin) headers.Origin = origin;
  if (method === "OPTIONS") headers["Access-Control-Request-Method"] = "GET";
  const response = await fetch(new URL(path, base), {
    method, headers, ...(body !== undefined ? { body: JSON.stringify(body) } : {}), signal: AbortSignal.timeout(10000)
  });
  return { status: response.status, headers: response.headers, body: await response.json().catch(() => null) };
}

async function check(name, run, needsToken = true) {
  if (needsToken && !token) { console.log("SKIP " + name + " (MEDICATION_ACCESS_TOKEN 필요)"); skipped++; return; }
  try { await run(); console.log("PASS " + name); passed++; }
  catch (error) {
    const reason = error.code === "ERR_ASSERTION" ? "응답 상태·필드 불일치" : error.name === "TimeoutError" ? "요청 시간 초과" : "요청 실패";
    console.log("FAIL " + name + " (" + reason + ")"); failed++;
  }
}

(async () => {
  if (!["http:", "https:"].includes(base.protocol) || base.username || base.password) throw new Error("HTTP(S) 서버 주소를 사용하세요.");
  await check("server-health", async () => {
    const result = await request("/actuator/health", { auth: false });
    assert.equal(result.status, 200); assert.equal(result.body.status, "UP");
  }, false);
  await check("calendar-requires-auth", async () => {
    const result = await request("/api/calendar?from=" + today + "&to=" + today, { auth: false });
    assert.equal(result.status, 401); assert.equal(result.body.code, "UNAUTHENTICATED");
  }, false);
  await check("calendar-rejects-invalid-token", async () => {
    assert.equal((await request("/api/calendar?from=" + today + "&to=" + today, { invalidToken: true })).status, 401);
  });
  await check("calendar-owner-and-contract", async () => {
    const path = "/api/calendar?from=" + today + "&to=" + today;
    const result = await request(path);
    assert.equal(result.status, 200); assert.equal(result.body.from, today); assert.equal(result.body.to, today);
    assert.ok(Array.isArray(result.body.schedules));
    for (const schedule of result.body.schedules) {
      assert.ok(Number.isSafeInteger(schedule.profileId) && Number.isSafeInteger(schedule.myDrugId));
      assert.ok(schedule.startDate <= today && today <= schedule.endDate);
      assert.ok(schedule.hasSchedule && Array.isArray(schedule.doseTimes));
    }
    const injected = await request(path + "&memberId=9223372036854775807");
    assert.equal(injected.status, 200); assert.deepEqual(injected.body, result.body);
    selected = result.body.schedules[0];
  });
  await check("daily-dose-contract", async () => {
    const result = await request("/api/calendar/doses?date=" + today);
    assert.equal(result.status, 200); assert.ok(Array.isArray(result.body.doses));
    for (const dose of result.body.doses) assert.match(dose.time, /^([01]\d|2[0-3]):[0-5]\d$/);
  });
  await check("invalid-calendar-date", async () => {
    assert.equal((await request("/api/calendar?from=2026-02-30&to=2026-03-01")).status, 400);
    assert.equal((await request("/api/calendar/doses?date=" + today + "&time=24:00")).status, 400);
  });
  await check("owned-drug-list-and-schedule", async () => {
    const profileId = process.env.MEDICATION_PROFILE_ID || selected?.profileId;
    const drugId = process.env.MEDICATION_MY_DRUG_ID || selected?.myDrugId;
    if (!profileId || !drugId) throw new Error("일정 없는 계정은 MEDICATION_PROFILE_ID와 MEDICATION_MY_DRUG_ID 지정 필요");
    const drugs = await request("/api/profiles/" + encodeURIComponent(profileId) + "/drugs/schedule-choices");
    assert.equal(drugs.status, 200); assert.ok(Array.isArray(drugs.body.drugs));
    const drug = drugs.body.drugs.find(item => String(item.myDrugId) === String(drugId));
    assert.ok(drug, "선택한 약이 현재 회원 목록에 없음");
    const schedule = await request("/api/profiles/" + encodeURIComponent(profileId) + "/drugs/" + encodeURIComponent(drugId) + "/schedule");
    assert.equal(schedule.status, drug.hasSchedule ? 200 : 404);
  });
  await check("unknown-profile-hidden", async () => {
    assert.equal((await request("/api/profiles/9223372036854775807/drugs/schedule-choices")).status, 404);
  });
  await check("notification-settings-contract", async () => {
    const result = await request("/api/notification-settings");
    assert.equal(result.status, 200); assert.equal(typeof result.body.pushEnabled, "boolean");
    assert.equal(typeof result.body.consentActive, "boolean"); assert.equal(typeof result.body.subscriptionCount, "number");
  });
  await check("push-vapid-readiness", async () => {
    const result = await request("/api/push/vapid-public-key");
    assert.equal(result.status, 200, "VAPID 설정 확인 필요: HTTP " + result.status);
    assert.equal(Buffer.from(result.body.publicKey, "base64url").length, 65);
  });
  await check("live-validation-without-paid-session", async () => {
    // 빈 본문은 DTO 검증에서 거절된다. 실제 세션·OpenAI 호출은 생성하지 않는다.
    assert.equal((await request("/api/medication/live/sessions", { method: "POST", body: {} })).status, 400);
    assert.equal((await request("/api/medication/live/sessions/verification-nonexistent")).status, 404);
  });
  await check("frontend-cors-preflight", async () => {
    const origin = process.env.MEDICATION_FRONTEND_ORIGIN || "http://localhost:5173";
    const result = await request("/api/calendar", { method: "OPTIONS", origin, auth: false });
    assert.equal(result.status, 200); assert.equal(result.headers.get("access-control-allow-origin"), origin);
  }, false);
  console.log(`RESULT passed=${passed} failed=${failed} skipped=${skipped}; DB 변경·실제 음성 연결·푸시 발송 없음`);
  process.exitCode = failed ? 1 : 0;
})().catch(() => { console.error("FAIL 서버 주소·연결 설정을 확인하세요."); process.exitCode = 1; });
