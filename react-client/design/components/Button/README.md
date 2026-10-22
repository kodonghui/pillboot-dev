# Button

사용자가 실행하는 동작 하나를 나타내는 버튼으로, 한 화면의 주 행동은 `mt-btn--solid` 하나만 둔다.

## 쓰는 법

`<button class="mt-btn mt-btn--{variant} mt-btn--{size}" type="button">라벨</button>`

소비하는 쪽이 정하는 것: 라벨 문구, `type`, 클릭 처리, 비활성일 때 `disabled`(링크라면 `aria-disabled="true"`). 꽉 찬 폭이 필요하면 `mt-btn--block`. 아이콘은 라벨 앞에 넣으면 `space-8` 간격이 들어간다.

## 변형 (목업 클래스와 짝)

| 클래스 | 목업 | 모양 | 언제 |
| --- | --- | --- | --- |
| `mt-btn--solid` | `btn-p` | `primary-normal` 채움, 흰 글자 | 화면의 주 행동: 저장, 로그인, 시작하기 |
| `mt-btn--assistive` | `btn-s` | 흰 면 + `line-normal` 테두리 | 취소, 다시 찍기, 약 보기 같은 보조 행동 |
| `mt-btn--ghost` | `btn-g` | 글자만 `primary-on-weak` | 뒤로 가기("약 상세로", "등록 방법으로"), 헤더 "로그인" |
| `mt-btn--danger` | `btn-d` | `status-negative` 채움 | 확인창의 삭제 실행 |
| `mt-btn--danger-outlined` | `btn-do` | 흰 면 + 빨간 테두리 · 글자 | 화면 안 "약 삭제", "일정 삭제" |
| `mt-btn--weak`, `mt-btn--outlined` | — | 목업에 없음 | 쓸 자리가 생기면 팀에서 정한다 |

비활성: 바탕 `interaction-disable`, 글자 `label-disable`.

## 크기

| 클래스 | 높이 | 글자 | 안쪽 좌우 |
| --- | --- | --- | --- |
| `mt-btn--lg` (기본) | 48px | 16px 600 | `space-20` |
| `mt-btn--md` | 44px | 15px 600 | `space-14` |

모서리는 크기와 상관없이 `radius-md`(10px). `mt-btn--sm` 은 예전 이름이라 `md` 와 같다. 누르는 영역이 44px보다 작은 버튼은 만들지 않는다.

## 지킬 것

- 라벨은 하는 일을 그대로: "일정 저장", "다시 찍기", "약 상세로". "확인" 하나로 뭉뚱그리지 않는다.
- 폼 맨 아래 버튼 줄은 오른쪽 정렬, 취소(assistive)가 왼쪽 · 실행(solid)이 오른쪽.
- 포커스는 3px `focus-ring` 외곽선. 지우지 않는다.
