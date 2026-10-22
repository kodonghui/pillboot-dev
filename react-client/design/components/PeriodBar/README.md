# PeriodBar

달력 한 주 줄 위에 약을 먹는 기간을 칸 너머로 이어 그리는 막대로, 복용 달력에 쓴다.

## 쓰는 법

일곱 칸 그리드(`grid-template-columns: repeat(7, minmax(0, 1fr))`) 안에서 시작 요일부터 끝 요일까지 걸쳐 놓는다.

```html
<span class="mt-period mt-period--orange" style="grid-row: 2; grid-column: 5 / 8;">타이레놀정 · 3일</span>
```

소비하는 쪽이 정하는 것: 주 단위로 기간을 잘라 `grid-column` 계산(주를 넘으면 다음 주 줄에 이어서), 약마다 색 배정, 줄 순서(짧게 끝나는 약을 위로).

## 색

| 클래스 | 토큰 | 언제 |
| --- | --- | --- |
| `mt-period` | `primary-weak` | 첫 번째 약 |
| `mt-period--blue` | `accent-weak-blue` | 두 번째 약 |
| `mt-period--violet` | `accent-weak-violet` | 세 번째 약 |
| `mt-period--orange` | `accent-weak-orange` | 3일처럼 짧게 끝나는 약, 글자 600 |

- 글자는 항상 `label-normal`, `caption1`. 막대 높이는 글자 한 줄, 모서리 `radius-chip`.
- 넷보다 많으면 색을 다시 돌려 쓰되, 같은 주 줄에 같은 색이 붙어 나오지 않게 한다.

## 지킬 것

- 색면과 바탕 대비가 낮으므로 막대 안에 약 이름을 꼭 쓴다. 색만으로 약을 구분하지 않는다.
- 막대가 좁아 이름이 잘리면 말줄임(…)으로 줄이고, 날짜를 누르면 그날 목록에서 전체 이름을 보여 준다.
