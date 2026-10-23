# Notice

화면 안에 놓이는 안내 · 주의 · 오류 블록으로, 사라지지 않고 그 자리에서 읽힌다.

## 쓰는 법

```html
<div class="mt-notice mt-notice--caution" role="status">
  <svg class="mt-notice__icon" …></svg>
  <p class="mt-notice__text"><b>복약 알림이 꺼져 있어요.</b> 켜면 복용 시간마다 크롬으로 알려 드려요.</p>
</div>
```

제목이 따로 필요하면 `mt-notice__body` 안에 `mt-notice__title` 과 `mt-notice__text` 를 쌓는다. 안에 버튼 하나(`mt-btn--assistive mt-btn--md`)를 오른쪽에 둘 수 있다(홈 "알림 켜기").

소비하는 쪽이 정하는 것: 단색 선 아이콘, 문구, 버튼. 바로 알려야 하는 오류는 `role="alert"`, 그 밖에는 `role="status"` 또는 없음.

## 변형

| 클래스 | 면 / 테두리 / 글자 | 언제 (목업 예) |
| --- | --- | --- |
| `mt-notice` | `background-alternative` / `line-normal` / `label-normal` | 일반 안내 |
| `mt-notice--info` | `background-status-info` / `line-status-info` / `label-status-info` | 서비스 약속, "진단이나 처방을 대신하지 않아요" |
| `mt-notice--caution` | `background-status-cautionary` / `line-status-cautionary` / `label-status-cautionary` | 알림 꺼짐, "다른 화면으로 가면 결과는 저장되지 않아요" |
| `mt-notice--negative` | `background-status-negative` / `line-status-negative` / `label-status-negative` | 오류, 응급 안내 |
| `mt-notice--positive` | `background-status-positive` / `line-status-positive` / `primary-on-weak` | 완료 · 문제 없음 |

- 글자 15px, 굵게 강조는 `<b>`. 글자와 아이콘이 같은 진한 색이라 모두 4.5:1을 넘는다.
- 안쪽 `space-14` × `space-16`, 모서리 `radius-lg`.

## 지킬 것

- 왼쪽 굵은 선 같은 장식은 쓰지 않는다. 면 · 테두리 · 아이콘으로 구분한다.
- 한 화면에 같은 위계의 Notice를 두 개 넘게 쌓지 않는다.
