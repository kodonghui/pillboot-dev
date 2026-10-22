# TextField

라벨 · 입력창 · 도움말이 한 묶음인 한 줄 입력칸으로, 폼의 모든 글자 입력에 쓴다.

## 쓰는 법

```html
<div class="mt-field">
  <label class="mt-field__label" for="email">이메일<span class="mt-field__req">*</span></label>
  <input class="mt-input" id="email" type="email">
  <p class="mt-field__help">로그인할 때 쓰는 이메일이에요.</p>
</div>
```

소비하는 쪽이 정하는 것: 라벨 문구, `id`와 `for`, `type`, 검증 결과에 따른 `aria-invalid="true"`와 오류 문구, `aria-describedby`로 도움말 연결.

## 상태

| 상태 | 입력창 | 도움말 |
| --- | --- | --- |
| 기본 | 1px `line-normal` | `mt-field__help` · `label-neutral` |
| 포커스 | 2px `primary-normal` | — |
| 오류 | `aria-invalid="true"` → 2px `status-negative` | `mt-field__help--error` · `accent-fg-red` 600 |
| 확인됨 | 기본과 같음 | `mt-field__help--ok` · `primary-on-weak` 600 |
| 비활성 | `interaction-disable` 바탕, `label-disable` 글자 | — |

- 높이 48px, 안쪽 여백 `space-14`, 모서리 `radius-sm`, 글자 `body1`.
- 플레이스홀더는 `label-assistive`. 꼭 읽어야 하는 안내는 플레이스홀더가 아니라 도움말에 쓴다.
- 필수 표시는 `mt-field__req`(\*). 색만으로 필수를 알리지 않도록 별표를 함께 쓴다.

## 지킬 것

- 오류는 색만 바꾸지 말고 무엇이 틀렸고 어떻게 고치는지 도움말에 쓴다.
- 화면에 라벨을 보이지 않게 할 때도 `label`은 남긴다(시각적으로만 숨김).
