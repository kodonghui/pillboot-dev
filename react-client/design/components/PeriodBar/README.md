# PeriodBar

복용 달력의 한 주 줄 위에 약을 먹는 기간을 칸 너머로 이어 그리는 막대로, 사람(프로필)마다 색이 다르다.

## 쓰는 법

일곱 칸 그리드 안에서 시작 요일부터 끝 요일까지 걸쳐 놓는다. 막대는 눌러서 일정 상세(SCH-05)를 여는 링크다.

```html
<a class="mt-period mt-period--p2" href="…" style="grid-row: 2; grid-column: 3 / 8;"><b>엄마</b>&nbsp;노바스크정 5밀리그램</a>
```

소비하는 쪽이 정하는 것: 주 단위로 기간을 잘라 `grid-column` 계산, 주를 넘어 이어지면 `mt-period--cont-l`(앞 주에서 이어짐) · `mt-period--cont-r`(다음 주로 이어짐), 프로필 순서에 맞는 색, 줄 순서.

## 색

| 클래스 | 면 / 테두리 / 글자 | 언제 |
| --- | --- | --- |
| `mt-period--p1` | `profile-1-bar` / `profile-1-bar-line` / `profile-1-ink` | 첫 번째 프로필(본인) |
| `mt-period--p2` | `profile-2-bar` / … / `profile-2-ink` | 두 번째 프로필 |
| `mt-period--p3` | `profile-3-bar` / … / `profile-3-ink` | 세 번째 프로필 |
| `mt-period--past` | 투명 면, 2px 점선 `profile-*`, 글자 `label-alternative` | 끝난 일정 |

- 높이 최소 44px(누르는 영역), 14px, 사람 이름은 `<b>`, 모서리 `radius-sm`.
- 프로필 색은 ProfileTag · 헤더 "관리할 사람" 점과 같은 순서로 맞춘다.

## 지킬 것

- 색만으로 사람을 구분하지 않는다. 막대 안에 사람 이름과 약 이름을 함께 쓴다.
- 이전 판의 "약마다 색"(`--blue` · `--violet` · `--orange`)은 목업과 달라 없앴다.
