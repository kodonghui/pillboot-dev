# Modal

화면 위에 떠서 한 가지 결정만 묻는 확인창으로, 삭제 · 그만 먹기 · 로그아웃 · 알림 수신 동의처럼 되돌리기 어렵거나 동의가 필요한 행동 앞에 쓴다.

## 쓰는 법

```html
<div class="mt-dim">
  <section class="mt-modal" role="dialog" aria-modal="true" aria-labelledby="t" aria-describedby="d">
    <h2 class="mt-modal__title" id="t">로그아웃할까요?</h2>
    <p class="mt-modal__text" id="d">로그아웃하면 로그인 화면으로 돌아가요.</p>
    <div class="mt-modal__actions">
      <button class="mt-btn mt-btn--assistive">취소</button>
      <button class="mt-btn mt-btn--solid">로그아웃</button>
    </div>
  </section>
</div>
```

소비하는 쪽이 정하는 것: 열기 · 닫기, Esc 처리, 포커스를 창 안에 가두고 닫을 때 원래 버튼으로 돌려보내기, 문구.

## 구성

- 막 `mt-dim` · `material-dimmer`. 창은 화면 위쪽(140px 아래)에 붙는다.
- 창 `mt-modal`: 너비 최대 520px, `background-elevated`, `radius-2xl`(16px), `shadow-xlarge`, 안쪽 `space-28`, 요소 사이 `space-16`.
- 제목 `screen-section`(21px 700), 설명 16px `label-neutral`.
- 버튼은 오른쪽 정렬: 왼쪽 취소(assistive), 오른쪽 실행. 위험한 실행(삭제)은 `mt-btn--danger`, 그 밖에는 `mt-btn--solid`.

## 목업에서 이 창을 쓰는 곳

로그아웃 확인(CMN-01), 약 삭제(MED-04), 그만 먹기 · 일정 삭제(SCH-05), 프로필 삭제(PRF-01), 알림 수신 동의(NTF-01). 음성 대화 팝업(SCH-04)은 따로 그린 큰 창(`radius-3xl`)이라 이 컴포넌트가 아니다.

## 지킬 것

- 제목은 질문으로, 무엇이 사라지거나 남는지 설명에 적는다("약은 약 목록에 남아요").
- 입력이 많은 일은 창 대신 화면으로 보낸다.
