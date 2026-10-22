# Toast

방금 한 일이 끝났다고 잠깐 알려 주는 막대로, 화면 아래 가운데에 떴다가 사라진다.

## 쓰는 법

```html
<div class="mt-toast" role="status">
  <svg …></svg>
  <span>타이레놀정500밀리그람을 엄마의 약에 담았어요</span>
  <a class="mt-toast__action" href="…">복용 기간 정하기</a>
</div>
```

소비하는 쪽이 정하는 것: 위치(화면 아래 가운데, 아래에서 `space-32`), 보여 주는 시간과 사라지는 처리, 문구, 이어서 할 일 링크 하나.

## 구성

- 바탕 `inverse-background`, 글자 `inverse-label`, 이어서 할 일 `inverse-primary` 600. 모서리 `radius-lg`, 그림자 `shadow-medium`.
- 폭은 내용에 맞추고 화면 폭에서 32px을 뺀 만큼까지만 넓어진다. 좁은 화면에서는 줄을 바꾼다.

## 지킬 것

- 끝난 일을 과거형으로 알린다: "담았어요", "저장했어요".
- 오류나 꼭 읽어야 하는 내용은 Toast가 아니라 Notice로 화면에 남긴다.
- 이어서 할 일 링크는 하나만 둔다.
