# Chip

하나 또는 여러 개를 고르는 선택 버튼(목업의 `opt`)으로, 실행 버튼 대신 쓰지 않는다.

## 쓰는 법

`<button class="mt-chip" type="button" aria-pressed="false">식후 30분</button>`

소비하는 쪽이 정하는 것: 라벨, 선택 상태(`aria-pressed`, 탭처럼 쓸 때 `aria-selected`), 하나만 고를지 여러 개 고를지의 로직, 묶음을 감싸는 `role="group"` 과 `aria-label`.

## 쓰는 자리 (목업)

- 프로필 만들기: 본인 / 가족, 성별, 임신 여부.
- 일정 추가 · 수정: 누구의 일정, 약 고르기, 기간 빠르게(3 · 7 · 14 · 30일), 복용 때(식전 · 식후 30분 · 식후 바로 · 자기 전).

## 상태

| 상태 | 면 | 글자 | 테두리 |
| --- | --- | --- | --- |
| 기본 | `background-elevated` (hover `background-alternative`) | `label-normal` 500 | `line-normal` |
| 선택 | `primary-weak` | `primary-on-weak` 700 | `line-primary-normal` |
| 비활성 | `interaction-disable` | `label-disable` | 없음 |

## 크기

- 기본: 높이 48px, 좌우 18px, 16px.
- `mt-chip--sm`: 높이 44px, 좌우 `space-14`, 15px (목업 `opt-s`, 기간 · 복용 때).
- 모서리 `radius-md`, 칩 사이 `space-8`.

## 지킬 것

- 선택을 색으로만 보여 주지 않는다. 선택 칩은 테두리 색과 글자 굵기도 바뀐다.
- 약처럼 두 줄이 필요한 선택지("지르텍정 / 일정 없음")는 칩 안에 `<small>` 한 줄을 더해 세로로 쌓는다.
