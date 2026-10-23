# Badge

항목 옆에 붙는 짧은 상태 표시로, 누를 수 없고 두세 단어까지만 쓴다.

## 쓰는 법

`<span class="mt-badge mt-badge--caution">확인 필요</span>`

소비하는 쪽이 정하는 것: 문구, 상태에 맞는 변형. 누를 수 있는 선택지는 Chip을 쓴다. 사람 이름은 Badge가 아니라 ProfileTag를 쓴다.

## 변형

| 클래스 | 면 / 글자 | 언제 (목업 예) |
| --- | --- | --- |
| `mt-badge` | `fill-normal` / `label-neutral` | 중립: "화면 예시", "기다리는 중", "일정 없음" |
| `mt-badge--primary` · `--positive` | `primary-weak` / `primary-on-weak` | 완료 · 선택: "골랐어요", "일정 있음" |
| `mt-badge--caution` | `background-status-cautionary` / `label-status-cautionary` | 주의: 병용 주의, 일부 확인 못 함 |
| `mt-badge--negative` | `background-status-negative` / `label-status-negative` | 금기 · 오류 |
| `mt-badge--info` | `background-status-info` / `label-status-info` | 진행 중 |

- 높이 28px, 좌우 10px, 14px 600, 모서리 `radius-full`. 아이콘을 앞에 넣을 수 있다(간격 6px).

## 지킬 것

- 상태를 색만으로 전하지 않는다. 문구가 뜻을 담는다.
- 한 줄에 배지를 세 개 넘게 붙이지 않는다.
