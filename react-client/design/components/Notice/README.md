# Notice

화면 안에 놓이는 안내 · 주의 · 오류 블록으로, 사라지지 않고 그 자리에서 읽힌다.

## 쓰는 법

```html
<div class="mt-notice mt-notice--caution" role="status">
  <svg class="mt-notice__icon" …></svg>
  <div class="mt-notice__body">
    <p class="mt-notice__title">사진 인식은 틀릴 수 있어요</p>
    <p class="mt-notice__text">이름과 용량이 약봉투와 같은지 꼭 확인해 주세요.</p>
  </div>
</div>
```

소비하는 쪽이 정하는 것: 아이콘(단색 선 아이콘), 제목, 설명, 필요하면 안에 넣을 버튼. 바로 알려야 하는 오류는 `role="alert"`, 그 밖에는 `role="status"` 또는 없음.

## 변형

| 클래스 | 바탕 | 아이콘 색 | 언제 |
| --- | --- | --- | --- |
| `mt-notice` | `fill-alternative` | `label-neutral` | 일반 안내 |
| `mt-notice--primary` | `primary-weak` | `primary-on-weak` | 서비스 약속 · 개인정보 안내 |
| `mt-notice--positive` | `background-status-positive` | `status-positive` | 문제 없음 확인 |
| `mt-notice--caution` | `background-status-cautionary` | `status-cautionary` | 확인 필요 · DUR 주의 |
| `mt-notice--negative` | `background-status-negative` | `status-negative` | 오류 · 금기 · 허가 취하 |

- 제목 `label1` 600, 설명 `caption1` · `label-neutral`. 글자는 언제나 `label-normal` 계열이고 색은 아이콘에만 준다.
- 안쪽 여백 `space-14` × `space-16`, 모서리 `radius-lg`.

## 지킬 것

- 왼쪽 굵은 선 같은 장식은 쓰지 않는다. 바탕색과 아이콘으로만 구분한다.
- 한 화면에 같은 위계의 Notice를 두 개 넘게 쌓지 않는다.
