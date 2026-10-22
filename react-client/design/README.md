필받음 화면은 Pillbox 토큰 위에서 만든다. Pillbox 는 Montage 를 바탕으로 필받음에 맞게 고친 디자인 시스템이다. 바탕은 무채색으로 조용하게 두고, 사용자가 눌러야 하는 곳에만 초록 primary 를 쓴다. 값은 `pillbadeum-tokens.json` 에 있는 토큰 이름으로만 고르고, 새 색·간격·모서리 값을 만들지 않는다.

## 원칙

- **초록은 행동에만.** `primary-normal` 은 채워진 버튼, 선택된 탭, 링크에만 쓴다. 장식, 일러스트 배경, 넓은 면에는 쓰지 않는다. 넓은 강조 면이 필요하면 `primary-weak` 를 깔고 그 위 글자·아이콘은 `primary-on-weak` 로 쓴다.
- **구분은 선이 먼저.** 평면 위 요소를 나눌 때는 `line-solid-normal` 1px 를 먼저 쓴다. 그림자는 카드가 실제로 떠 있을 때만 쓴다.
- **반투명 토큰은 겹쳐 쓰지 않는다.** `line-normal`, `fill-normal` 같은 반투명 값은 겹치면 색이 누적된다. 선이 겹치는 자리에는 `line-solid-normal` 을 쓴다.
- **테마는 토큰이 바꾼다.** 컴포넌트에 라이트/다크 분기를 직접 쓰지 않는다. 모든 색은 `var(--토큰이름)` 으로 참조한다.

## 색

### 바탕과 면

| 자리 | 토큰 |
| --- | --- |
| 페이지 바탕 | `background-normal` |
| 카드가 올라가는 한 단계 들어간 영역 | `background-alternative` |
| 카드·시트·모달 | `background-elevated` |
| 툴팁·스낵바 | `inverse-background` + 글자 `inverse-label`, 액션 `inverse-primary` |
| 모달 뒤 막 | `material-dimmer` |

다크 테마에서 `background-elevated` 는 `background-normal` 보다 밝다. 떠 있는 면을 그림자 대신 이 밝기 차이로 보여 준다.

### 글자

- 본문과 제목은 `label-normal` 부터 쓴다. 가장 강한 제목만 `label-strong`.
- 한 단계 약한 글자는 `label-neutral` (흰 바탕에서 9:1 이상).
- `label-alternative` 는 캡션·메타데이터용이지만 흰 바탕 대비가 3.7:1 정도라 4.5:1 에 못 미친다. 12–14px 에서 꼭 읽혀야 하는 글자에는 `label-neutral` 을 쓴다.
- `label-assistive` 는 플레이스홀더 전용이다. 읽어야 하는 글자에는 쓰지 않는다.
- 비활성 글자는 `label-disable`, 비활성 컨트롤 바탕은 `interaction-disable`.

### Primary (초록)

| 상태 | 채움 | 글자 |
| --- | --- | --- |
| 기본 | `primary-normal` | `static-white` |
| hover | `primary-strong` | `static-white` |
| press | `primary-heavy` | `static-white` |
| 약한 면 | `primary-weak` | `primary-on-weak` |
| 약한 면 hover/press | `primary-weak-strong` | `primary-on-weak` |
| 경계선 | `line-primary-normal` | — |

흰 글자는 모든 primary 채움 위에서 4.5:1 이상이다. 다만 다크 테마에서 `primary-normal` 을 `background-normal` 위 **글자**로 쓰면 3.6:1 이라, 다크에서 본문 크기 링크는 `primary-on-weak` 를 쓴다.

### 상태

`status-positive`, `status-cautionary`, `status-negative` 는 점, 아이콘, 테두리에 쓴다. 메시지 블록의 바탕은 같은 이름의 `background-status-*` 를 깐다. 라이트 테마에서 세 색 모두 흰 바탕 위 글자 대비가 4.5:1 미만이므로, 상태 문구 자체는 `label-normal` 로 쓰고 색은 아이콘에만 준다. 성공과 오류를 색만으로 구분하지 않는다. 항상 "저장했어요", "다시 입력해 주세요" 같은 문구나 아이콘을 함께 둔다.

### 액센트

- `accent-bg-*` 는 배지·칩의 색면이다. 흰 글자를 올리게 되어 있지만 `accent-bg-violet` 을 뺀 나머지는 흰 글자 대비가 3:1 미만이다. 이 색면 위 글자는 `label2` 굵기로 두세 글자만 쓰고, 뜻은 배지 밖 문장에서도 전한다.
- `accent-fg-*` 는 `background-normal` 위 글자·아이콘 색이다. 색면에는 쓰지 않는다. 라이트 테마에서 4.5:1 을 넘는 것은 `accent-fg-red`, `accent-fg-blue`, `accent-fg-violet`, `accent-fg-purple` 뿐이다. 나머지는 24px 이상 제목이나 아이콘에만 쓴다.

### 연한 색면

여러 항목을 색으로 나눠 보여야 하지만 화면이 시끄러우면 안 되는 자리에 쓴다. 대표 쓰임은 복용 달력의 약별 기간 막대다.

| 토큰 | 라이트 / 다크 | 쓰는 곳 |
| --- | --- | --- |
| `primary-weak` | #e2f2eb / #173a2f | 첫 번째 항목 |
| `accent-weak-blue` | #e5efff / #1a2b47 | 두 번째 항목 |
| `accent-weak-violet` | #ece8fe / #2a2252 | 세 번째 항목 |
| `accent-weak-orange` | #fff1e0 / #3d2a14 | 짧게 끝나는 항목. 예: 3일 처방 약 |

- 위에 올리는 글자는 항상 `label-normal` 이다. 라이트 · 다크 모두 12:1 이상이다.
- 색면과 페이지 바탕의 대비는 1.1~1.3:1 로 낮다. 색만으로 항목을 구분하지 말고 막대 안에 약 이름을 함께 쓴다.
- 색면 위에 `accent-fg-*` 나 `static-white` 글자를 올리지 않는다.

### 채움과 선

- 보조 버튼과 칩의 채움은 `fill-normal`, hover 는 `fill-strong`. 스켈레톤과 슬라이더 트랙은 `fill-alternative`.
- 기본 경계선은 `line-normal`, 한 단계 약하게는 `line-neutral`. 겹치는 자리는 `line-solid-normal`.
- 꺼진 토글 트랙은 `interaction-inactive`.

## 글자

글꼴은 Pretendard 하나다 (`font-sans`). 굵기는 400, 500, 600, 700 네 가지만 쓴다.

| 그룹 | 스타일 | 쓰는 곳 |
| --- | --- | --- |
| Display | `display1` / `display2` / `display3` | 랜딩 히어로(화면당 하나), 섹션 대제목 |
| Title | `title1` / `title2` / `title3` | 페이지 제목, 모달 제목 |
| Heading | `heading1` / `heading2` | 섹션, 하위 섹션 제목 |
| Headline | `headline1` / `headline2` | 카드·리스트 항목 제목 |
| Body | `body1`, `body2` 와 `-reading` 변형 | 본문. 여러 문단이면 `-reading` |
| Label | `label1` / `label1-reading` / `label2` | 버튼, 탭, 폼 라벨, 칩 |
| Caption | `caption1` / `caption2` | 메타데이터, 배지 숫자 |

- 좁은 화면(`breakpoint-sm` 미만)에서는 `display2` 대신 `display3`, `title1` 대신 `title2` 를 쓴다.
- 큰 글자일수록 자간이 음수로 좁아지고 작은 글자일수록 넓어진다. 스타일 클래스(`.title1` 등)를 그대로 쓰고 자간을 따로 덮어쓰지 않는다.
- 숫자가 세로로 줄을 맞춰야 하는 표에는 `font-variant-numeric: tabular-nums` 를 준다.

## 간격

간격은 `space-*` 스케일 안에서만 고른다.

- 아이콘과 글자 사이 `space-4`, 칩 안쪽 좌우 `space-8`, 리스트 항목 사이 `space-12`.
- 카드 안쪽 여백은 `space-16`, 넓은 카드는 `space-20`.
- 섹션 사이 `space-24`, 넓게는 `space-32`. 페이지 상하 여백 `space-40`.
- `space-0_5`, `space-1`, `space-2` 는 선 두께나 미세 정렬용이다. 레이아웃 간격으로 쓰지 않는다.

## 모서리

| 토큰 | 값 | 쓰는 곳 |
| --- | --- | --- |
| `radius-chip` | 6px | 작은 칩 |
| `radius-sm` | 8px | small 버튼, 칩, 인풋 |
| `radius-md` | 10px | medium 버튼 |
| `radius-lg` | 12px | large 버튼과 카드. 기본값 |
| `radius-full` | 9999px | 아바타, 알약형 배지 |

안쪽 요소의 모서리는 바깥 요소보다 작게 한다. `radius-lg` 카드 안의 버튼은 `radius-md` 이하.

## 그림자

평면 위 구분은 `line-solid-normal` 이 먼저다. 그림자는 떠 있는 정도에 맞춰 한 단계만 고른다.

- `shadow-xsmall` 살짝 떠 있는 입력창 · `shadow-small` 카드 hover · `shadow-medium` 드롭다운, 팝오버 · `shadow-large` 바텀시트 · `shadow-xlarge` 모달.
- 다크 테마에서는 그림자가 잘 보이지 않는다. 다크에서 떠 있는 면은 `background-elevated` 로 구분한다.

## 레이아웃

모바일 우선으로 짜고 `min-width` 로 올린다: `breakpoint-sm` 768px(태블릿 세로), `breakpoint-md` 992px(태블릿 가로), `breakpoint-lg` 1200px(데스크톱), `breakpoint-xl` 1600px(와이드).

## 상태와 포커스

- hover, press 는 같은 계열의 한 단계 진한 토큰으로 바꾼다 (`primary-normal` → `primary-strong` → `primary-heavy`, `fill-normal` → `fill-strong`).
- 키보드 포커스는 2px `primary-normal` 외곽선을 요소 바깥 2px 에 띄운다. 흰 바탕에서 5.75:1, 다크 바탕에서 3.62:1 로 둘 다 3:1 이상이다.
- 비활성은 바탕 `interaction-disable`, 글자 `label-disable`. 비활성 컨트롤에는 hover 를 주지 않는다.

## 컴포넌트

아래 9가지가 있다. 클래스와 상태는 각 컴포넌트의 가이드에 있고, 스타일은 모두 `components/bundle.css` 에 있다.

| 컴포넌트 | 클래스 | 쓰는 곳 |
| --- | --- | --- |
| Button | `mt-btn` | 실행 버튼. 위험한 행동은 `mt-btn--danger` |
| Chip | `mt-chip` | 필터 · 하나 고르기 |
| Card | `mt-card` | 내용 한 묶음 |
| TextField | `mt-field` · `mt-input` | 폼의 글자 입력 |
| Badge | `mt-badge` | 짧은 상태 표시 (누를 수 없음) |
| Notice | `mt-notice` | 화면 안 안내 · 주의 · 오류 블록 |
| Modal | `mt-dim` · `mt-modal` | 되돌리기 어려운 행동 확인 |
| Toast | `mt-toast` | 끝난 일 잠깐 알림 |
| PeriodBar | `mt-period` | 복용 달력의 기간 막대 |

상태를 알리는 세 가지는 쓰임을 나눈다: 잠깐 알리고 사라질 때 Toast, 화면에 남겨 읽혀야 할 때 Notice, 사용자의 결정이 필요할 때 Modal.

## 로고와 아이콘

이 시스템에는 로고와 아이콘 파일이 없다. 서비스 이름은 `title3` 이상의 Pretendard 700 으로 글자 그대로 쓴다. 아이콘 세트가 정해지기 전까지 아이콘은 글자 색 토큰(`label-normal`, `primary-on-weak` 등)을 따르는 단색 선 아이콘으로 쓰고, 이모지는 쓰지 않는다.

## 문구

- 버튼은 하는 일을 그대로 쓴다: "저장", "다시 시도", "로그인".
- 오류는 무엇이 잘못됐고 어떻게 고치는지 쓴다: "비밀번호가 8자보다 짧아요".
