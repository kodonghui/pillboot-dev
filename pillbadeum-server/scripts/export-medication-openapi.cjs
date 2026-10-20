"use strict";
// 실행 중인 서버 명세에서 복약지도 경로와 참조하는 components만 추출한다. 사용자 데이터는 읽지 않는다.
const fs = require("node:fs");
const path = require("node:path");
const base = new URL(process.env.MEDICATION_BASE_URL || "http://localhost:8080");
const belongs = pathname => /^\/api\/(calendar(?:\/doses)?|profiles\/\{profileId\}\/drugs(?:\/schedule-choices|\/\{myDrugId\}\/schedule(?:\/stop)?)|medication\/live\/sessions(?:\/\{sessionId\})?|notification-settings|push\/(?:vapid-public-key|subscriptions))$/.test(pathname);

(async () => {
  const response = await fetch(new URL("/v3/api-docs", base), { signal: AbortSignal.timeout(15000) });
  if (!response.ok) throw new Error("OpenAPI 조회 실패: HTTP " + response.status);
  const source = await response.json();
  if (!source.openapi || !source.paths) throw new Error("OpenAPI 문서가 아님");
  const paths = Object.fromEntries(Object.entries(source.paths).filter(([pathname]) => belongs(pathname)));
  if (!paths["/api/calendar"] || !paths["/api/medication/live/sessions"]) throw new Error("필수 복약지도 경로 누락");
  const components = {}, visited = new Set();
  function collect(value) {
    if (!value || typeof value !== "object") return;
    if (typeof value.$ref === "string") {
      const match = value.$ref.match(/^#\/components\/([^/]+)\/([^/]+)$/);
      if (!match) throw new Error("지원하지 않는 외부 참조");
      if (!visited.has(value.$ref)) {
        visited.add(value.$ref);
        const section = match[1].replace(/~1/g, "/").replace(/~0/g, "~");
        const name = match[2].replace(/~1/g, "/").replace(/~0/g, "~");
        const component = source.components?.[section]?.[name];
        if (!component) throw new Error("참조 컴포넌트 누락");
        (components[section] ||= {})[name] = component;
        collect(component);
      }
    }
    for (const child of Object.values(value)) collect(child);
  }
  collect(paths);
  if (source.components?.securitySchemes) components.securitySchemes = source.components.securitySchemes;
  const spec = {
    openapi: source.openapi,
    info: { title: "필받음 복약지도 API", version: source.info?.version || "1.0",
      description: "복용 예정 일정·Live·알림. 실제 복용 여부 체크는 제공하지 않습니다. 연결 순서는 MEDICATION_FRONTEND_HANDOFF.md 참고." },
    servers: [{ url: "http://localhost:8080" }],
    ...(source.security ? { security: source.security } : {}),
    paths, components
  };
  const output = path.join(__dirname, "../docs/MEDICATION_OPENAPI.json");
  fs.writeFileSync(output, JSON.stringify(spec, null, 2) + "\n");
  console.log("PASS OpenAPI export: " + Object.keys(paths).length + " paths, " + Object.keys(components.schemas || {}).length + " schemas");
})().catch(error => { console.error(error.message); process.exitCode = 1; });
