필받음 화면은 Pillbox 토큰 위에서 만든다. Pillbox 는 Montage 를 바탕으로 필받음 목업 v2(「필받음 목업 (작업용)」, 보드 101개)에 맞춰 고친 디자인 시스템이다. 회색 페이지 바탕 위에 흰 카드를 올리고, 사용자가 눌러야 하는 곳에만 초록 primary 를 쓴다. 가족마다 색(파랑 · 주황 · 보라)을 하나씩 정해 모든 화면에서 같은 사람은 같은 색으로 보인다. 값은 `tokens.json` 의 토큰 이름으로만 고르고, 새 색 · 간격 · 모서리 값을 만들지 않는다.

> 기준: 라이트 테마 값은 2026년 10월 21일 목업 v2에서 옮겼다. 목업에 다크 화면이 없어 다크 테마 값은 이전 Pillbox 값을 그대로 두었고, 목업 기준으로 확인하지 않았다. 새로 더한 토큰(상태 테두리 · 글자, 프로필, 포커스)은 라이트 값만 있다.

## 원칙

- **초록은 행동에만.** `primary-normal` 은 채워진 버튼, 지금 메뉴 밑줄, 오늘 날짜, 진행 표시에만 쓴다. 넓은 강조 면은 `primary-weak` 를 깔고 그 위 글자는 `primary-on-weak`.
- **회색 바탕, 흰 카드, 선으로 구분.** 페이지는 `background-normal`(#F4F6F8), 카드 · 헤더 · 입력창은 `background-elevated`(흰색) + 1px `line-normal`. 그림자는 모달 · 드롭다운처럼 실제로 떠 있을 때만.
- **사람은 색과 이름으로.** 프로필 색은 프로필 순서로 정하고(`profile-1` 본인부터), 태그 · 달력 막대 · 헤더 점에서 같은 색을 쓴다. 색만으로 사람을 구분하지 않고 이름을 늘 함께 쓴다.
- **테마는 토큰이 바꾼다.** 컴포넌트에 색 값을 직접 쓰지 않고 `var(--토큰이름)` 으로 참조한다.

## 색

### 바탕과 면

| 자리 | 토큰 |
| --- | --- |
| 페이지 바탕 | `background-normal` #F4F6F8 |
| 카드 · 헤더 · 입력창 · 모달 · 말풍선 | `background-elevated` #FFFFFF |
| 카드 안에서 한 단계 들어간 영역(대화 메시지 영역, 달력 요일 줄, 근거 목록, 읽기 전용 입력) | `background-alternative` #F8FAFB |
| 모달 · 음성 팝업 뒤 막 | `material-dimmer` |

### 글자

| 토큰 | 값 | 쓰는 곳 | 흰 바탕 대비 |
| --- | --- | --- | --- |
| `label-normal` | #17212B | 본문과 제목 | 15:1 이상 |
| `label-neutral` | #44505C | 부제, 폼 라벨, 메뉴 글자 | 8.2:1 |
| `label-alternative` | #5E6A76 | 캡션 · 메타 · 도움말(14px) | 5.5:1 (회색 바탕 5.1:1) |
| `label-assistive` | #6B7682 | 플레이스홀더 전용 | — |
| `label-disable` | #55606B | 비활성 버튼 글자(`interaction-disable` #E3E7EB 위 5.2:1) | — |

14px보다 작은 글자는 쓰지 않는다.

### Primary (초록)

| 상태 | 채움 | 글자 |
| --- | --- | --- |
| 기본 | `primary-normal` #0B6B4F | `static-white` (6.5:1) |
| hover | `primary-strong` #085A42 | `static-white` |
| 약한 면 | `primary-weak` #E3F1EA | `primary-on-weak` #075B43 (7.0:1) |
| 선택 테두리 | `line-primary-normal` | — |

링크 글자는 `primary-on-weak`.

### 상태

상태 블록 · 배지는 연한 면 + 테두리 + 진한 글자 세 토큰이 한 벌이다. 글자와 아이콘을 같은 진한 색으로 써서 모두 4.5:1을 넘는다.

| 상태 | 면 | 테두리 | 글자 · 아이콘 | 쓰는 곳 |
| --- | --- | --- | --- | --- |
| 안내 | `background-status-info` | `line-status-info` | `label-status-info` | 서비스 약속, 의료 면책 |
| 주의 | `background-status-cautionary` | `line-status-cautionary` | `label-status-cautionary` | 알림 꺼짐, 병용 주의, 저장 안 됨 경고 |
| 오류 · 금기 | `background-status-negative` | `line-status-negative` | `label-status-negative` | 입력 오류, 응급 안내, 병용 금기 |
| 완료 | `background-status-positive` | `line-status-positive` | `primary-on-weak` | 저장 완료 |

- 위험 버튼 바탕은 `status-negative` #B42318(흰 글자 6.6:1). 일요일 · 공휴일 날짜 글자는 `label-status-negative`.
- `status-cautionary`(원색 주황)는 점 · 아이콘에만 쓴다. 글자로 쓰지 않는다.
- 성공과 오류를 색만으로 구분하지 않는다. 문구와 아이콘을 함께 둔다.

### 프로필(가족) 색

| 순서 | 점 | 태그 면 | 글자 | 달력 막대 면 / 테두리 |
| --- | --- | --- | --- | --- |
| 1 (본인) | `profile-1` #2F66C2 | `profile-1-weak` | `profile-1-ink` | `profile-1-bar` / `profile-1-bar-line` |
| 2 | `profile-2` #DB7A1F | `profile-2-weak` | `profile-2-ink` | `profile-2-bar` / `profile-2-bar-line` |
| 3 | `profile-3` #5B3A9E | `profile-3-weak` | `profile-3-ink` | `profile-3-bar` / `profile-3-bar-line` |

- 태그 글자는 각 면 위에서 6.3:1 이상이다.
- 목업 달력에는 세 번째 사람의 막대가 없어 `profile-3-bar` 는 1번과 같은 투명도로 맞췄다.
- 이전 판의 "약마다 색"(`accent-weak-*`) 달력 막대는 목업과 달라 쓰지 않는다. `accent-*` 토큰은 남아 있지만 목업 화면에서 쓰는 곳이 없다.

### 포커스

키보드 포커스는 3px `focus-ring`(#6FB39A) 외곽선을 요소 바깥 2px에 띄운다. **주의:** 흰 바탕 대비가 2.4:1로 비텍스트 기준 3:1에 못 미친다. 목업 값이라 그대로 두었고, 바꿀지 팀에서 정한다(`primary-normal` 2px로 바꾸면 6.5:1).

## 글자

글꼴은 Pretendard 하나다(`font-sans`, 목업은 Pretendard Variable). 굵기 400 · 500 · 600 · 700.

화면은 **Screen 그룹**을 쓴다. 목업 v2의 크기 그대로다.

| 스타일 | 크기 / 행간 / 굵기 | 쓰는 곳 |
| --- | --- | --- |
| `screen-hero` | 40 / 1.3 / 700 | 시작 화면 큰 제목(좁은 화면 30) |
| `screen-time` | 56 / 1 / 600 | 홈 · 이 시각 약의 시각 숫자(좁은 화면 44), tabular-nums |
| `screen-title` | 30 / 1.35 / 700 | 페이지 제목 h1(좁은 화면 24) |
| `screen-section` | 21 / 1.4 / 700 | 카드 · 섹션 제목, 모달 제목 |
| `screen-item` | 18 / 1.45 / 600 | 항목 제목, 폼 섹션 제목 |
| `screen-body` | 16 / 1.6 / 400 | 본문 · 부제 · 입력 |
| `screen-button` | 16 / 1.2 / 600 | 버튼(작은 버튼 15) |
| `screen-label` | 14 / 1.6 / 600 | 폼 라벨, 배지 |
| `screen-caption` | 14 / 1.6 / 400 | 도움말 · 메타 · 캡션 |

Display · Title · Heading 등 Montage 그룹은 남아 있지만 목업 화면에는 쓰지 않는다. 한국어는 `word-break: keep-all` 로 단어 단위로 줄을 바꾼다.

## 간격

`space-*` 스케일 안에서 고른다. 목업에서 자주 쓰는 값:

- 아이콘과 글자 `space-8`, 버튼 사이 `space-12`, 폼 섹션 사이 `space-20`.
- 카드 안쪽 `space-24`(넓은 카드 · 모달 `space-28`), 화면 큰 덩어리 사이 `space-28`.
- 페이지 상단 `space-40`, 하단 `space-72`, 본문 최대 폭 1280px · 좌우 48px(좁은 화면 16px). 폼은 최대 760px.

## 모서리

| 토큰 | 값 | 쓰는 곳 |
| --- | --- | --- |
| `radius-chip` | 6px | 스켈레톤 줄 같은 아주 작은 요소 |
| `radius-sm` | 8px | 달력 기간 막대 |
| `radius-md` | 10px | 버튼 · 입력창 · 선택 버튼 · 메뉴 항목. 크기 공통 |
| `radius-lg` | 12px | 카드 안 작은 상자: 안내 블록, 근거 목록, 사진 자리 |
| `radius-xl` | 14px | 카드, 달력 틀 |
| `radius-2xl` | 16px | 모달, 말풍선(꼬리 쪽 모서리 4px) |
| `radius-3xl` | 20px | 음성 대화 팝업 |
| `radius-full` | 9999px | 배지, 프로필 태그, 관리할 사람 버튼 |

## 그림자

평면 위 구분은 선이 먼저다. `shadow-medium` 드롭다운(설정 · 관리할 사람), `shadow-xlarge` 모달. 카드에는 그림자를 쓰지 않는다.

## 화면 구조 (사이트맵과 맞춤)

자세한 흐름은 「필받음 사이트맵 (최종)」에 있다. 시스템 쪽에서 지킬 것만 적는다.

- **헤더(AppHeader).** 로그인 후 주 메뉴는 홈 · 약 목록 · 복용 달력 · 챗봇 · 설정 다섯 가지. 설정은 드롭다운(프로필 관리 · 알림). 오른쪽에 관리할 사람 ▾ · 닉네임 · 로그아웃. 관리할 사람 버튼은 홈 · 약 목록 · 약 등록 · 챗봇에서만 보이고, 약 등록 도중에는 막고, 그 밖의 화면에서는 숨긴다.
- **메뉴 이름.** "캘린더"가 아니라 "복용 달력"이다.
- **겹쳐 뜨는 것은 네 가지로 나눈다.** 확인창(Modal): 삭제 · 그만 먹기 · 로그아웃 · 알림 수신 동의. 드롭다운(`mt-hd__menu`): 설정 · 관리할 사람. 패널: 복용 달력 오른쪽 일정 상세. 큰 팝업: 음성 대화.
- **뒤로 가기는 들어온 곳으로.** 화면 위쪽 왼쪽에 `mt-btn--ghost` 로 "‹ 약 상세로"처럼 돌아갈 곳 이름을 쓴다. 같은 양식을 여러 곳에서 열면(일정 추가 · 일정 수정) 뒤로 가기 · 취소 · 저장 모두 들어온 곳으로 돌아간다.
- **폼 아래 버튼 줄**은 오른쪽 정렬, 취소 왼쪽 · 실행 오른쪽.

## 상태와 포커스

- hover 는 한 단계 진한 토큰으로 바꾼다(`primary-normal` → `primary-strong`, 흰 면 → `background-alternative`).
- 비활성은 바탕 `interaction-disable`, 글자 `label-disable`. hover 를 주지 않는다.
- 누르는 영역은 최소 44px.

## 컴포넌트

스타일은 모두 `components/bundle.css` 에 있다. 목업 클래스 이름과 짝을 맞춰 두었다.

| 컴포넌트 | 클래스 | 목업 | 쓰는 곳 |
| --- | --- | --- | --- |
| AppHeader | `mt-hd` | `hd` | 모든 화면 위 |
| Button | `mt-btn` | `btn` | 실행 버튼. solid · assistive · ghost · danger |
| Chip | `mt-chip` | `opt` | 하나 · 여러 개 고르기 |
| Card | `mt-card` | `panel` | 내용 한 묶음 |
| TextField | `mt-field` · `mt-input` | `field` · `inp` | 글자 입력 |
| Badge | `mt-badge` | `bdg` | 짧은 상태 표시 |
| ProfileTag | `mt-ptag` | `ptag` | 누구의 약 · 일정인지 |
| Notice | `mt-notice` | `note` | 화면 안 안내 · 주의 · 오류 블록 |
| Modal | `mt-dim` · `mt-modal` | `scrim` · `dlg` | 확인창 |
| PeriodBar | `mt-period` | `bar` | 복용 달력 일정 막대 |
| Toast | `mt-toast` | 없음 | 목업에 쓰는 화면이 없다 |

목업에는 있지만 아직 컴포넌트로 만들지 않은 것: 챗봇 말풍선 · 근거 목록, 음성 대화 팝업, 달력 틀, 홈 시각 슬롯, 오류 화면(403 · 404) 틀. 화면을 만들 때는 목업 보드의 마크업을 따른다.

## 로고와 아이콘

로고 파일은 없다. 헤더 로고는 34px 초록 사각형(`radius` 10px) 안의 알약 선 아이콘 + "필받음" 20px 700 글자다. 아이콘은 24px 격자의 단색 선 아이콘(선 1.8)으로 글자 색을 따르고, 이모지는 쓰지 않는다.

## 문구

- 버튼은 하는 일을 그대로: "일정 저장", "다시 찍기", "약 상세로".
- 오류는 무엇이 잘못됐고 어떻게 고치는지: "이미 가입한 이메일이에요".
- 확인창 제목은 질문으로, 설명에 무엇이 남는지 적는다: "약은 약 목록에 남아요".
