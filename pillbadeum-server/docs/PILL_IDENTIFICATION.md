# 낱알 식별 · REG-007 v1

읽힌 각인 한 면 이상 + 공식 목록에서 확인되는 모양 + 색상 한 가지 이상을 필수로 검사함. 부족하면 후보를 반환하지 않고 보완 입력·재촬영·이름 검색을 안내함. 처방전과 이름 검색은 기존 경로를 유지함.

## 비교와 표시

- 각인/분할선 상태는 READABLE·NONE·UNREADABLE임. NONE은 선명한 면에 표시가 없음, UNREADABLE은 못 읽음이며 둘 다 값은 null임. 기존 빈 문자열은 UNREADABLE임.
- 사진1/사진2는 업로드 순서임. 공식 앞뒤 두 대응을 검사하므로 사용자가 공식 앞면을 구분할 필요가 없음.
- 각인은 NFKC·대문자·공백만 정규화함. O/0·I/1·10/40 자동 치환이나 유사도·가중치를 사용하지 않음.
- 명백히 다른 각인·분할선은 제외함. 최소 각인 한 면·모양·관찰한 모든 색상이 맞아야 후보임. 제품명으로 모순을 보상하지 않음.
- 실제 각인 일치 면 수 → 확인된 분할선 일치 수로 순서를 정함. 같은 근거는 같은 evidenceRank임. 코드순은 표시 안정성용이며 확률을 뜻하지 않음.
- matchDetails[].result=MATCH만 일치 배지로 표시함. 공식 빈 값은 UNKNOWN, 미판독 입력은 NOT_COMPARED임. 새 상세 결과에 similarityScore는 없으며 호환 응답의 동명 필드는 null임.
- 유효 공식 후보를 품목코드로 중복 제거한 뒤 전체 수를 계산함. 최대5개를 반환하고 전체6개 이상이면 미리보기만 제공하며 서버도 저장을 차단함.

## HTTP 연결

- 낱알 OCR·후보 작업은 정식/호환 경로 모두 전체85초 마감을 공유함. OCR read timeout·HTTP408/504 뒤 자동 재호출하지 않음. 파일 업로드·DB·프록시 시간을 포함한 절대 응답 시간 보장은 아님.
- 낱알 후보의 ingredientName은 공식 성분명 전체임. 새 DB는 최신01, 기존 DB는 SQL12로 material을 TEXT로 확장함. 과거 잘린 값의 자동 복원은 별도 작업임. 단서 비교·동률 정책과 후보 수 제한은 유지함.
- OCR의 shape·color1·color2는 공식 목록의 한글 값 또는 null임. hexagonal·light green 등의 알려진 이전 영문 표현도 공식 값으로 정규화함. 읽지 못한 항목을 추정해 채우거나 각인 혼동 문자를 치환하지 않음.

상세 응답은 기존 사진 응답 외피에 contractVersion=1·identificationId·origin·registrationSource·expiresAt·reason·후보 수·선택 가능 여부·missingFields·nextActions를 추가함. X-Pill-Identification-Version: 1 헤더를 제공함. JWT와 프로필 소유권 확인이 필요함.

| 동작 | 요청 | 응답 |
| --- | --- | --- |
| 정식 사진 식별 | POST /api/profiles/{profileId}/photo-identifications?kind=PILL, front/back 각1장 | PHOTO 상세 결과임. |
| 단서 보완/직접 검색 | POST /api/images/identify?view=details&profileId={id}, 단서 JSON | AI 호출 없이 CLUES 상세 결과임. |
| 결과 복원 | GET /api/profiles/{profileId}/pill-identifications/{identificationId} | 당시 단서·후보·근거를 복원하고 현재 등록 여부만 갱신함. |
| 취소 | DELETE /api/profiles/{profileId}/pill-identifications/{identificationId} | 204이며 연결 사진도 정리함. |
| 기존 사진 재조회 | GET /api/photos/{photoId}/candidates | PILL은 같은 v1 결과를 반환함. 이전 정책 사진은409로 재식별을 요구함. |
| 호환 단서 검색 | POST /api/images/identify, view 생략 또는 legacy | 기존 후보 배열임. 부족400·유효0건 빈 배열임. 결과 ID를 만들지 않음. |
| 호환 보조 사진 | POST /api/images/pill-photo/identify | 기본1~2장의 extracted/candidates 외피를 유지함. view=details&profileId={id}이면 정식2장 흐름과 연결함. |

- 상세 업무 결과는 부족200 UNREADABLE, 유효0건200 NOT_FOUND, 후보 있음200 OK임. reason/nextActions로 화면을 연결함. N>5는 TOO_MANY_CANDIDATES임.
- 기존 공통 오류 외피 code/message를 유지함. 입력400의 실제 code는 VALIDATION_FAILED, 비소유 결과404, 교체/선택 불가409, 남아 있는 만료 행410, 제공자/목록/DB 준비 실패503임.
- 재검색의 optional query previousIdentificationId는 같은 프로필 ACTIVE 결과를 원자적으로 대체함. 새 업무 부족/0건은 대체하며 형식 오류/외부 실패는 이전 결과를 유지함.
- ACTIVE 복원 응답과 달리 CANCELLED/REPLACED/CONSUMED는 최소 상태 헤더만 남김. REPLACED는 replacementIdentificationId, CONSUMED는 첫 registrationReceipt를 반환함.

## 선택 저장·임시 보관

POST /api/profiles/{profileId}/drugs에 다음 items 계약으로 한 후보만 보냄. PHOTO는 PILL_PHOTO+photoId, 보완/직접 입력 CLUES는 PILL_CLUES+photoId=null임. 서버가 결과의 실제 출처·후보·수명·선택 개수를 다시 확인함.

```json
{
  "source": "PILL_CLUES",
  "identificationId": "서버가 반환한 UUID",
  "photoId": null,
  "items": [{"itemSeq": "반환된 공식 숫자 코드"}]
}
```

- 같은 결과 ID·같은 품목은 첫 HTTP 상태와 saved/skipped 영수증을 재전송함. 다른 품목 재전송은409임. 저장 후 약을 삭제해도 재전송으로 재생성하지 않음.
- PHOTO의 photoId만 보내던 기존 items 요청은 v1 결과가 있을 때만 연결함. drugs 방식·옛 보조 등록 API로 PILL_PHOTO/PILL_CLUES를 우회 저장할 수 없음. SEARCH·처방전·MANUAL 기존 계약은 유지함.
- 새 설치용 최신01은 낱알 결과 구조까지 포함함. 기존01·03~09만 적용한 DB는 10_PILL_IDENTIFICATION_SESSION.sql을 대상 DB에 명시적으로 실행함. PILL_CLUES는 기존 source VARCHAR(30)에 들어감.
- CLUES/실패 사진 결과는 생성 후30분, 사진 성공은 사진 업로드 후30분을 넘지 않음. snapshot은 구조화 단서·최대5개 공식 후보/근거·목록 SHA만64KiB 이하로 보관함. 사진 bytes·모델 원문은 보관하지 않음.
- 취소·대체·소비 시 snapshot을 비우며 기존 사진 정리 주기에 만료 행 정리를 연결함. 새 스케줄러를 만들지 않음. 프로필/회원 삭제는 FK로 정리함.
- 프런트는 회원·프로필별 sessionStorage에 identificationId만 기억하고 GET으로 복원함. 미제출 편집 값은 보관하지 않음. 로그아웃·프로필 전환·취소 시 참조를 정리함.
- PILL_IDENTIFICATION_ENABLED=false이면 상세 낱알 진입만503으로 닫으며 이름 검색·처방전과 기존 TTL 정리는 유지함. PILL_CLUES 읽기 지원이 없는 과거 빌드로 되돌리지 않음. SQL10을 즉시 DROP하거나 출처를 일괄 치환하지 않음.

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

실제 API의 해당 품목은 `197400040`, 공식 제품명은 `인데놀정10mg(프로프라놀롤염산염)`이다. 이 품목과 실제 각인 MATCH 근거가 반환되고 similarityScore는 null인지 확인함. 과거 명칭 `인데놀정10밀리그램`을 하드코딩해 다른 약의 이름을 바꾸지 않는다.

코드 수정 후에는 이 프로젝트의 서버를 재시작해야 한다. Swagger를 새로고침하는 것만으로 실행 중인 Java 코드가 바뀌지는 않는다.

## Swagger 응답 표시

- 단서/사진 검색의 상세·호환 응답을 oneOf로 구분함. 결과 복원에는 ACTIVE 상세와 종료 상태의 새 ID/등록 영수증을 표시함.
- items 등록은201(saved/skipped)·전부 건너뜀200, 기존 drugs 등록은201(상세 배열), 결과/사진 취소는204·본문 없음으로 표시함. 런타임 계약은 유지함.
- 실제 자동 검증 수치와 실행 범위는 해당 기능 PR의 검증 결과를 확인함. 사진1쌍의 판독 결과를 모델 전체 정확도로 표시하지 않음.
