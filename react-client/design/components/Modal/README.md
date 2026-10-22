# Modal

화면 위에 떠서 한 가지 결정만 묻는 창으로, 삭제 · 로그아웃 · 동의 철회처럼 되돌리기 어려운 행동 앞에 쓴다.

## 쓰는 법

```html
<div class="mt-dim">
  <div class="mt-modal" role="alertdialog" aria-modal="true" aria-labelledby="t" aria-describedby="d">
    <h2 class="mt-modal__title" id="t">이 약을 삭제할까요?</h2>
    <p class="mt-modal__text" id="d">되돌릴 수 없어요.</p>
    <div class="mt-modal__actions">
      <button class="mt-btn mt-btn--assistive mt-btn--lg">취소</button>
      <button class="mt-btn mt-btn--danger mt-btn--lg">삭제</button>
    </div>
  </div>
</div>
```

소비하는 쪽이 정하는 것: 열기 · 닫기 상태, Esc와 바깥 클릭 처리, 포커스를 모달 안에 가두고 닫을 때 원래 버튼으로 돌려보내기, 제목 · 설명 · 버튼 문구.

## 구성

- 막: `mt-dim` · `material-dimmer`. 창: `mt-modal` · `background-elevated`, `radius-lg`, `shadow-xlarge`, 안쪽 여백 `space-24`, 너비 최대 440px(`mt-modal--wide` 560px).
- 제목 `heading1`, 설명 `body2` · `label-neutral`.
- 버튼은 오른쪽이 실행, 왼쪽이 취소. 두 버튼은 같은 너비로 나눠 쓴다.
- 위험한 행동의 실행 버튼은 `mt-btn--danger`(`accent-fg-red` 바탕, 흰 글자 4.6:1). 그 밖에는 `mt-btn--solid`.

## 지킬 것

- 제목은 질문으로 쓰고, 무엇이 사라지거나 바뀌는지 설명에 숫자와 함께 적는다.
- 입력이 많은 일은 모달 대신 화면으로 보낸다. 모달 안 입력칸은 세 개까지.
