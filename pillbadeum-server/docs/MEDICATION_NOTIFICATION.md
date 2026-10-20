# 복약 알림(웹 푸시)

Functional Spec ver.4의 NOTI-001(알림 켜기)·NOTI-002(알림 끄기)·NOTI-003(복약 알림 보내기) 구현입니다.
웹 푸시는 크롬 기준이며, 새 라이브러리 없이 JDK 암호화와 기존 JJWT로 RFC 8291(aes128gcm)·RFC 8292(VAPID)를 따릅니다.

## API

모두 로그인(Bearer JWT)이 필요합니다. 기존 보안 설정의 `anyRequest().authenticated()` 규칙을 그대로 씁니다.

| 요청 | 동작 |
| --- | --- |
| `GET /api/notification-settings` | `{ pushEnabled, consentActive, consentPolicyVersion, subscriptionCount }` |
| `PUT /api/notification-settings` | 켜기 `{ pushEnabled: true, notificationConsent: { agreed, policyVersion } }`, 끄기 `{ pushEnabled: false }` → `{ pushEnabled, consentAgreedAt, consentRevokedAt }` |
| `GET /api/push/vapid-public-key` | `{ publicKey }`. 서버 키가 없으면 503 |
| `POST /api/push/subscriptions` | `{ endpoint, keys: { p256dh, auth }, userAgent }` → 처음 201, 같은 브라우저 200 `{ subscriptionId }` |

화면 순서: 동의 확인(GET 설정) → 브라우저 허용 → 공개 키로 구독 → 구독 저장(POST) → 켜기(PUT).

## 알림 수신 동의

실제 DB의 약관 구조를 따릅니다. 명세의 `consent.type·revoked_at` 대신 아래로 판단합니다.

- 약관: `consent`의 `terms_type = 'MEDICATION_REMINDER'` 중 활성·시행 중인 최신 약관
- 동의 상태: 그 약관에 대한 회원의 가장 최근 `consent_history`가 `AGREED`면 동의, `WITHDRAWN`이면 철회
- 켜기: 동의가 없으면 `notificationConsent.agreed=true`와 현재 약관 버전이 필요합니다. 없거나 버전이 다르면 409 `CONSENT_REQUIRED`
- 끄기: 동의 철회 `WITHDRAWN` 행 추가, `push_enabled=false`, 그 회원의 모든 브라우저 구독 삭제. 이미 꺼져 있으면 그대로 200
- 다시 켜면 이전 행은 두고 새 `AGREED` 행을 만듭니다. 설정 행을 잠근 한 트랜잭션에서 처리합니다.

## 브라우저 구독

- `push_subscription.endpoint` UNIQUE(대소문자 구분). 같은 브라우저는 1행이며 다른 회원 것이었으면 지금 회원으로 옮깁니다.
- 서버가 이 주소로 직접 요청을 보내므로 `web-push.allowed-hosts`의 https 푸시 서비스 주소만 받습니다.
- 명세 ver.4대로 '이 브라우저만 해제' API는 없습니다. 알림 끄기가 모든 브라우저 구독을 지웁니다.

## 알림 보내기와 중복 방지

매분 0초(한국 시간) 스케줄러가 그 분을 처리합니다.

1. 서버 키가 없으면 건너뜁니다.
2. `notification_dispatch`에 그 분을 먼저 넣습니다. 이미 있으면 다른 실행(재시작·서버 여러 대)이 맡은 것이므로 건너뜁니다.
3. 알림을 켠 회원의 약 중 오늘이 복용 기간 안이고 이 시:분이 복용 시각인 약을 회원별로 셉니다.
   일정 없는 약(세 값 NULL), 그만 먹기로 어제 끝난 약, 삭제·수정으로 시각이 빠진 약은 대상이 아닙니다.
4. 회원 1명 = 알림 1건. 그 회원의 구독 브라우저마다 같은 내용을 가상 스레드로 동시에 보냅니다(5초 제한, 재시도 없음).
5. 404·410이면 그 구독 행을 지우고, 그 밖의 실패는 로그만 남깁니다.

알림 내용은 `{ title, body, url, tag }`이며 약 이름·프로필 이름은 넣지 않습니다.
예: '아침에 드실 약이 3개예요. 눌러서 확인해 주세요.', `url=/doses?date=2026-10-16&time=07:00`, `tag=dose-2026-10-16-07:00`.
같은 시각 알림은 푸시 서비스의 `Topic`과 브라우저의 `tag`로 한 번 더 하나로 합쳐집니다. 유효 시간(TTL)은 1시간입니다.
서버가 멈춰 있던 지난 시각은 다시 보내지 않습니다. `notification_dispatch`에는 시각만 남고 2일이 지나면 지웁니다.

## 설정

| 값 | 설명 |
| --- | --- |
| `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY`, `VAPID_SUBJECT` | 셋을 함께 넣거나 모두 비웁니다. 비우면 서버는 뜨고 푸시 API만 503, 스케줄러는 건너뜀 |
| `WEB_PUSH_ALLOWED_HOSTS` | 구독 주소로 허용할 푸시 서비스. 점으로 시작하면 하위 주소 전체 |
| `WEB_PUSH_SCHEDULER_ENABLED` | 기본 true. 테스트 프로필은 false |

기존 DB에는 `sql/03_MEDICATION_UPDATE.sql` 전체를 실행하고 마지막 상태 READY를 확인합니다. 이미 적용된 항목은 건너뜁니다.
화면 쪽에는 서비스 워커(예: `/sw.js`)에서 `push` 이벤트의 JSON으로 `showNotification(title, { body, tag, data: { url } })`을 하고,
`notificationclick`에서 `url`을 여는 처리가 필요합니다. 웹 푸시는 HTTPS에서만 동작합니다(개발 중 localhost 예외).

## 확인

`requests/medication-notification.http`에 요청 예시가 있습니다.
테스트는 RFC 8291 예시 값으로 암호화를 확인하고, 로컬 HTTP 서버로 성공·404·410·429·503 처리와 VAPID 서명을 확인합니다.
실제 크롬 브라우저로 받는 확인은 화면의 서비스 워커가 생긴 뒤 따로 해야 합니다.
