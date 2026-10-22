# Badge

항목 옆에 붙는 짧은 상태 표시로, 누를 수 없고 두세 단어까지만 쓴다.

## 쓰는 법

`<span class="mt-badge mt-badge--positive">해당 없음</span>`

소비하는 쪽이 정하는 것: 문구, 상태에 맞는 변형. 누를 수 있는 선택지는 Badge가 아니라 Chip을 쓴다.

## 변형

| 클래스 | 바탕 | 점 | 언제 |
| --- | --- | --- | --- |
| `mt-badge` | `fill-normal` | `interaction-inactive` | 중립 · 예정 · 꺼짐 |
| `mt-badge--primary` | `primary-weak` | `primary-normal` | 선택됨 · 지금 보는 중 · 새로 담음 |
| `mt-badge--positive` | `background-status-positive` | `status-positive` | 문제 없음 · 일치 |
| `mt-badge--caution` | `background-status-cautionary` | `status-cautionary` | 확인 필요 · 후보 여러 개 |
| `mt-badge--negative` | `background-status-negative` | `status-negative` | 금기 · 허가 취하 · 오류 |
| `mt-badge--plain` | 그대로 | 없음 | 분류 표시(전문의약품, 처방전 사진) |

- 글자는 `caption1` 600, 색은 `label-normal`(primary만 `primary-on-weak`). 모서리 `radius-full`.

## 지킬 것

- 상태를 색과 점만으로 전하지 않는다. 문구가 뜻을 담는다.
- 한 줄에 배지를 세 개 넘게 붙이지 않는다.
