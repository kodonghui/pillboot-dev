# 알약 식별 후보 검색

`POST /api/images/identify`는 각인·모양·색상·분할선 JSON을 받고, `POST /api/images/pill-photo/identify`는 `front` / `back` 사진을 받아 같은 비교기를 사용한다. 상태 있는 사진 등록 API의 `kind=PILL`도 동일한 카탈로그와 비교기를 사용한다. 제품명과 업체명이 없어도 식별 단서가 하나 이상 있으면 검색할 수 있다.

사진 응답의 `extracted`는 사진에서 읽은 정보다. 대조하여 찾은 공식 제품명은 `candidates[0].itemName`에 있다. `extracted.itemName`에 후보 이름을 덮어쓰지 않는다.

## 비교 기준

- `null`, 빈 문자열 및 문자열 `"null"`은 점수 계산에서 제외한다.
- 양면 각인 완전 일치, 양면 OCR 유사 일치, 일부 각인 일치 순으로 우선한다. 두 사진의 앞뒤가 바뀌어도 대조한다.
- 각인 단서가 있으면 모양·색상만 일치하는 약은 후보에 넣지 않는다.
- 색상 동의어(흰색/하양/백색), 공백·기호·전각 문자, 일부 OCR 혼동을 정규화한다. 서로 다른 숫자 용량을 유사한 숫자로 취급하지 않는다.
- 관련 후보를 최대 5개 반환한다. 관련 후보가 부족할 때 무관한 약으로 5개를 채우지 않는다. `similarityScore`는 식별 단서 일치도이며 약의 동일성을 보장하는 확률이 아니다.

## 카탈로그 준비와 갱신

공식 데이터: 식품의약품안전처 의약품 낱알식별 정보 (`MdcinGrnIdntfcInfoService03/getMdcinGrnIdntfcInfoList03`). 이 API의 실응답에서 `numOfRows=999`는 오류 11, `numOfRows=100`은 정상임을 확인했다.

전체 페이지를 100건씩 수집하여 `.cache/pill-identification/catalog.json`에 공개 식별 필드만 원자적으로 저장한다. 인증키·JWT·사진·개인정보는 저장하지 않는다. 이 파일은 Git에서 제외된다. 식별 HTTP 요청 안에서는 전체 목록을 내려받지 않는다.

서버 시작 및 6시간 주기로 백그라운드 갱신한다. 갱신 실패 시 마지막 정상 카탈로그를 유지한다. 초기 자료가 없으면 즉시 `503 PILL_CATALOG_PREPARING`을 반환하고 적재를 시작한다. 적재 중 요청을 장시간 잠그거나 일부 페이지만으로 무관한 후보를 만들지 않는다.

최초 로컬 준비와 실제 자료 검증:

```powershell
.\gradlew.bat syncPillCatalog --args=refresh
```

이미 저장된 카탈로그 검증(외부 API 호출 없음):

```powershell
.\gradlew.bat syncPillCatalog
```

이 명령은 공개 식별자료를 조회하며 OpenAI·DB는 호출하지 않는다. `.env`의 `PUBLIC_DATA_SERVICE_KEY`는 메모리에서만 읽고 출력하지 않는다.

## Swagger 재현 입력

```json
{
  "itemName": null,
  "entpName": null,
  "printFront": "DK",
  "printBack": "10",
  "shape": "원형",
  "colorClass1": "흰색",
  "colorClass2": null,
  "lineFront": null,
  "lineBack": null
}
```

실제 API의 해당 품목은 `197400040`, 공식 제품명은 `인데놀정10mg(프로프라놀롤염산염)`이다. 이 품목이 1순위, 식별 단서 점수 100으로 반환되는지 확인한다. 과거 명칭 `인데놀정10밀리그램`을 하드코딩해 다른 약의 이름을 바꾸지 않는다.

코드 수정 후에는 이 프로젝트의 서버를 재시작해야 한다. Swagger를 새로고침하는 것만으로 실행 중인 Java 코드가 바뀌지는 않는다.
