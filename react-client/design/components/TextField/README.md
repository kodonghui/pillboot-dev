# TextField

라벨 · 입력창 · 도움말이 한 묶음인 한 줄 입력칸으로, 폼의 모든 글자 입력에 쓴다.

## 쓰는 법

```html
<div class="mt-field">
  <label class="mt-field__label" for="email">이메일</label>
  <input class="mt-input" id="email" type="email" aria-describedby="email-h">
  <p class="mt-field__help" id="email-h">로그인할 때 쓰는 이메일이에요.</p>
</div>
```

소비하는 쪽이 정하는 것: 라벨, `id`와 `for`, `type`, 검증 결과에 따른 `aria-invalid="true"`와 오류 문구, `aria-describedby`.

## 상태

| 상태 | 입력창 | 도움말 |
| --- | --- | --- |
| 기본 | 흰 면, 1px `line-normal` | `label-alternative` 14px |
| 포커스 | 3px `focus-ring` 외곽선 | — |
| 오류 | `aria-invalid="true"` → 테두리 `status-negative` | `mt-field__help--error` · `label-status-negative` 500, 앞에 아이콘 |
| 확인됨 | 기본과 같음 | `mt-field__help--ok` · `primary-on-weak` 600 |
| 읽기 전용 · 비활성 | `background-alternative` 면, `label-neutral` 글자 | — |

- 높이 52px, 좌우 `space-16`, 모서리 `radius-md`, 글자 16px. 앞에 아이콘이 있으면 왼쪽 여백 46px.
- 라벨 14px 600 `label-neutral`. 필수 · 선택 표시는 라벨 뒤 `mt-field__req`에 "(필수)" · "(선택)" 글자로 쓴다.
- 플레이스홀더는 `label-assistive`. 꼭 읽어야 하는 안내는 도움말에 쓴다.
