# AppHeader

모든 화면 위에 붙는 흰 띠로, 로고 · 주 메뉴 · 관리할 사람 · 닉네임 · 로그아웃을 담는다. 사이트맵의 "공통 구조"가 이 컴포넌트다.

## 쓰는 법

```html
<header class="mt-hd"><div class="mt-hd__in">
  <a class="mt-hd__logo" href="/"><span class="mt-hd__mark">…아이콘…</span>필받음</a>
  <nav class="mt-hd__nav" aria-label="주 메뉴">
    <a href="/" aria-current="page">홈</a><a href="/drugs">약 목록</a><a href="/calendar">복용 달력</a><a href="/chat">챗봇</a>
    <a href="#" aria-haspopup="true" aria-expanded="false">설정</a>
  </nav>
  <div class="mt-hd__r">
    <button class="mt-hd__who" aria-haspopup="true" aria-expanded="false"><span class="mt-hd__who-k">관리할 사람</span><span class="mt-hd__who-dot"></span><span class="mt-hd__who-v">엄마</span></button>
    <span class="mt-hd__nick">하나님</span>
    <button class="mt-btn mt-btn--assistive mt-btn--md">로그아웃</button>
  </div>
</div></header>
```

소비하는 쪽이 정하는 것: 지금 화면의 메뉴에 `aria-current="page"`, 드롭다운 열기 · 닫기, 관리할 사람을 보일지 · 막을지, 점 색(`--profile-*`).

## 구성 (목업 v2 · 사이트맵 기준)

| 자리 | 로그인 전 | 로그인 후 |
| --- | --- | --- |
| 왼쪽 | 로고 "필받음" | 로고(→ 홈) |
| 가운데 | 없음 | 주 메뉴 5개: 홈 · 약 목록 · 복용 달력 · 챗봇 · 설정 |
| 오른쪽 | "로그인"(`mt-btn--ghost`) · "시작하기"(`mt-btn--solid mt-btn--md`) | 관리할 사람 ▾ · 닉네임 · 로그아웃 |

- **설정**은 화면이 아니라 드롭다운(`mt-hd__menu`)이다: "프로필 관리" · "알림" 두 줄.
- **관리할 사람**도 드롭다운이다: 본인 · 가족 목록(프로필 색 점), 맨 아래 "프로필 관리". 고르면 약 목록과 챗봇이 그 사람 기준으로 바뀐다.
- **로그아웃**은 바로 나가지 않고 확인창(Modal)을 띄운다.
- 주 메뉴에 없는 것: 약 등록, 일정 추가, 프로필 관리, 알림. 다른 화면의 버튼이나 설정 드롭다운으로 들어간다.

## 관리할 사람 버튼 보이기 규칙

| 화면 | 버튼 |
| --- | --- |
| 홈, 약 목록, 약 등록 방법(MED-02), 챗봇 | 보이고 바꿀 수 있다 |
| 약 등록 도중(IMG-01~06, MED-03) | 보이지만 바꿀 수 없다(`disabled`, 점선 테두리) |
| 복용 달력, 약 상세, 일정 추가 · 수정, 알림, 프로필 관리, 오류 화면 | 숨긴다 |

## 크기

- 높이 68px, 안쪽 최대 1280px · 좌우 48px(좁은 화면 16px, 메뉴는 줄을 바꿔 가로 스크롤).
- 메뉴 글자 16px 500, 지금 화면은 700 + 아래 3px `primary-normal` 밑줄.
- 관리할 사람 버튼: 높이 44px, 알약 모양, 1px `line-normal`.
