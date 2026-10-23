# Card

관련 있는 내용 한 묶음을 담는 흰 면(목업의 `panel`)으로, 회색 페이지 바탕(`background-normal`) 위에 올려 쓴다.

## 쓰는 법

```html
<section class="mt-card" aria-labelledby="t">
  <h2 class="mt-card__title" id="t">오늘 먹을 약</h2>
  <p class="mt-card__body">본문</p>
  <div class="mt-card__actions">…버튼…</div>
</section>
```

소비하는 쪽이 정하는 것: 제목 단계(`h2`/`h3`), 내용, 버튼, 카드 전체가 눌리면 링크 또는 `tabindex="0"`과 키보드 처리. 폭은 감싸는 레이아웃이 정한다.

## 변형

| 클래스 | 모양 | 언제 |
| --- | --- | --- |
| `mt-card` | `background-elevated`, 1px `line-normal`, `radius-xl`(14px), 안쪽 `space-24` | 기본. 폼 섹션, 약 상세 정보, 홈 할 일 |
| `mt-card--wide` | 안쪽 `space-28` | 로그인 · 회원가입 카드, 분석 중 카드 |
| `mt-card--flush` | 안쪽 0 | 머리 줄 · 목록 줄을 카드 끝까지 긋는 카드(홈 오늘 먹을 약, 챗봇 대화) |
| `mt-card--emphasis` | `primary-weak` 면 | 화면에서 하나만 강조할 안내 |
| `mt-card--interactive` | hover 에 `shadow-small` | 카드 전체가 눌릴 때 |

## 안쪽 구성

- 제목 `screen-section`(21px 700), 본문 `screen-body` · `label-neutral`, 메타 `screen-caption` · `label-alternative`.
- 요소 사이 `space-16`. 카드 안 목록 줄은 1px `line-neutral` 로 나눈다.
- 카드 사이 `space-24`. 좁은 화면에서 안쪽 18px.

## 지킬 것

- 평면 카드에 그림자를 쓰지 않는다. 테두리로 나눈다.
- 카드 안에 카드를 넣지 않는다. 한 단계 들어간 영역은 `background-alternative` 면으로 만든다.
