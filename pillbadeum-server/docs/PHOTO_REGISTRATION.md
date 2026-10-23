# 사진으로 약 등록 · REG-002~007 통합

## 적용과 범위

새 DB는 최신 SQL01로 구조를 생성하고 SQL13으로 기본 가입 약관을 준비함. 기존 DB는 누락된03~12 패치와13을 명시적으로 적용함. 기존 데이터는 보존하며 앱이 업무 DB에 자동 적용하지 않음. 낱알 v1의 필수 단서·후보 수·결과 복원·저장 계약은 [PILL_IDENTIFICATION.md](PILL_IDENTIFICATION.md)가 기준임.

이 흐름은 사진에서 약 후보를 찾아 사용자가 선택한 약만 등록하는 기능임. 별도 대화형 약 식별 에이전트는 백로그임. 모델은 이름·제약사·각인 등 단서만 읽으며, 공식 코드·전문/일반·실제 후보는 식약처 조회 결과에서만 가져옴. 후보가 맞는지 사용자가 확인하기 전에는 등록하지 않음.

## 정식 HTTP 계약

로그인 Bearer JWT가 필요함. 경로의 프로필은 로그인 회원의 것이어야 함.

| 동작 | 경로와 입력 | 결과 |
| --- | --- | --- |
| 처방전·약봉투 식별 | `POST /api/profiles/{profileId}/photo-identifications`, multipart `kind=PRESCRIPTION` 또는 `MEDICINE_BAG`, `image` 1장 | 200, `photoId`, `photoIds`, `status`, 약별 `drugs` |
| 알약 식별 | 같은 POST, `kind=PILL`, `front`·`back` 각 1장 | 200, 대표 앞면 `photoId`, 앞뒤 `photoIds`, `candidates`, `printMatched` |
| 후보 재조회 | `GET /api/photos/{photoId}/candidates` | 저장된 공식 후보와 현재 `alreadyRegistered`를 다시 계산함 |
| 원본 확인 | `GET /api/photos/{photoId}/image` | 본인 이미지 바이트·올바른 MIME·Cache-Control no-store임 |
| 나가기·다시 찍기 | `DELETE /api/photos/{photoId}` | 204. 알약은 앞뒤 그룹을 함께 지움 |
| 선택 저장 | `POST /api/profiles/{profileId}/drugs`, 아래 JSON | 신규가 있으면201, 전부 기등록이면200. `saved`·`skipped` 목록임 |

`images` 배열 입력도 허용하지만 처방전은 정확히1장, 알약은 정확히2장이어야 함. 단일 입력과 섞거나 같은 필드를 중복한 파일을 보내면400임. 원본은 장당10MB 이하의 실제 JPEG·PNG·WEBP만 허용함. 확장자·MIME·디코딩 형식이 같아야 함. 모델에는 EXIF를 제외하고 최대2048px JPEG로 보냄. 디코딩 한도는3200만 픽셀임.

```json
{
  "source": "PRESCRIPTION_PHOTO",
  "photoId": 123,
  "items": [{"itemSeq": "공식_숫자_품목코드"}]
}
```

예시의 photoId는 설명용임. itemSeq 자리는 실제 후보의 숫자 코드로 바꿔야 함. 알약 사진은 `PILL_PHOTO`, 단서 보완은 `PILL_CLUES`이며 상세 결과의 identificationId와 후보 한 개를 보냄. 이름 검색은 `SEARCH`, `photoId` 없이 같은 items를 사용함. 이름 검색·처방전은 코드 중복을 제거하고 기등록 약은 skipped로 반환함. 낱알은 요청 항목 한 개만 허용하며 같은 결과 ID의 재전송은 첫 HTTP 상태·영수증을 그대로 반환함.

새 선택 계약은 복용 일정을 자동 생성하지 않음. 현재 DB의 필수 dose_per_time에는 초기값1을 두되 날짜·시각은 NULL임. 이 값은 사진에서 읽거나 권장한 복용량이 아니며, 일정 저장 화면에서 사용자가 실제 값·기간·시각을 입력해야 함.

### 처방전 사진·검색 보완을 한 번에 저장

- 같은 처방전 결과의 미검색/실패 약을 이름 검색으로 보완한 뒤 한 요청으로 저장함. items[].source는 약별 출처이며 생략하면 최상위 source를 사용하므로 기존 요청도 유효함.
- 혼합은 최상위 PRESCRIPTION_PHOTO에서 SEARCH만 추가로 허용함. 사진 후보는 기존 소속 검증을 유지하고 검색 보완 약은 공식 품목코드를 서버가 다시 확인함. 다른 출처를 섞거나 같은 처방전 자리의 대안 후보를 여러 개 저장하면400임.
- readNo는 식별 응답 drugs[].readNo 값임. 사진이 남아 있는 혼합 저장에서는 검색 보완 약의 readNo가 필수임. 새 화면에서는 사진 약에도 readNo를 함께 보내 자리를 명시함.

```json
{
  "source": "PRESCRIPTION_PHOTO",
  "photoId": 123,
  "items": [
    {"itemSeq": "123456789", "source": "PRESCRIPTION_PHOTO", "readNo": 1},
    {"itemSeq": "987654321", "source": "SEARCH", "readNo": 2}
  ]
}
```

- 코드·ID는 설명용이며 실제 식별 결과와 검색 결과의 값으로 대체함. 전체 NOT_FOUND라 photoId가 null이면 검색 보완 약만 SEARCH로 보내며 읽은 이름과 슬롯은 화면에서 유지함.
- 검색 품목의 공식 확인은 등록 트랜잭션 밖에서 수행함. 하나라도 실패하면 my_drug는 전혀 저장하지 않고 남은 사진을 보존함. drug 공식 캐시 갱신은 먼저 일어날 수 있음.
- 공식 확인 뒤 약별 출처와 함께 한 트랜잭션으로 저장하며 성공 후 사진을 정리함. 기등록 약은 기존 출처를 바꾸지 않고 skipped로 반환함.
- candidates[].ingredientName은 한글 공식 성분 우선·영문 대체 값이며 material TEXT에 전체 보존함. 기존 DB는 SQL12가 필요함. 과거에 잃어버린 성분 문자열은 자동 복구하지 않음.

## 상태와 장애

- `OK`: 실제 후보가 하나 이상이며 성공 사진을 확인 완료까지 유지함. 약별 `lookup`은 OK / NOT_FOUND / FAILED로 구분함. 일부 이름 조회 실패가 나머지 후보를 없애지 않음.
- `UNREADABLE`: 처방전 글자·사진 종류·알약 수가 유효하지 않거나, 알약의 읽힌 각인 한 면·모양·색상 필수 단서가 부족한 경우임. 200 업무 결과이며 photoId/photoIds는 NULL임.
- `NOT_FOUND`: 읽기는 성공했으나 실제 공식 후보가 전부0건인 경우임. 200이며 사진·후보를 정리함.
- 모델 오류·전체 공식 조회 실패는503임. 파일/개수 오류400, 미로그인401, 프로필 소유권 위반403, 다른 회원의 사진은404임.
- 처방전 모델 대기 최대60초·후보 작업 최대40초·외부 작업 전체85초 예산임. 낱알은 모델·후보 작업이 같은85초 마감을 공유하며 양면 사진이 각각85초를 받는 방식이 아님. 외부 작업에는 DB 쓰기를 맡기지 않아 시간 초과 후 늦은 결과가 후보를 다시 만들지 못함. 업로드·DB·HTTP 인프라까지 포함한 절대85초 보장은 아님.
- 정식·기존 보조 사진 경로 모두 OCR read timeout 및 HTTP408/504를 자동 재호출하지 않음. 연결 장애·429·그 밖의 일시5xx만0.5초 뒤 최대1회 재시도하며 전체 마감을 넘기지 않음. 외부 제공자의 이미 시작한 계산 중단까지 보장하는 의미는 아님.
- 이름은 최대10개, 이름당 후보 최대5개임. 처방전은 허가08 조회 결과를 비교하며 낱알은 준비된 전체 공식 카탈로그를 대조함. 알약 각인은 양면을 바꾼 대응도 검사함.
- 공식 응답의 전문/일반은 반드시 확인함. 필수 외형/성분 열에 자료가 없는 경우 `미확인`으로 표시함. 기존 drug 캐시의 상세 값을 빈 정보로 덮어쓰지 않음. 취하 여부는 후보 재조회에서 NULL일 수 있으며 확정 근거·복약 안내는 기존 공식 근거 API가 별도로 검증함.

## 저장과 정리 경계

```mermaid
flowchart LR
  A["본인 프로필·사진 검사"] --> B["UPLOADED 짧은 저장"]
  B --> C["TX 밖 비전·공식 조회"]
  C --> D["후보·IDENTIFIED 짧은 저장"]
  D --> E["본인 재조회·사용자 선택"]
  E --> F["프로필 잠금·후보 소속 검증·my_drug 커밋"]
  F --> G["원본·후보 정리"]
  F -.-> H["기존 COMMON-005 DUR 후처리"]
```

`photo.front_photo_id`가 뒷면을 대표 앞면과 연결하며 `ocr_candidate.item_seq`가 공식 drug를 참조함. 키워드만 있던 이전 후보의 NULL 코드를 임의로 채우지 않음. 같은 프로필의 선택 등록은 프로필 행 잠금과04 복합 UNIQUE로 보호함. 사진 출처인데 후보에 없는 새 약·다른 프로필·사진 종류가 맞지 않으면 저장하지 않음. 명시적인 SEARCH 보완은 위 혼합 저장 검증을 따름.

정리 IO 실패는 이미 커밋된 약 등록 성공을 바꾸지 않음. 재시도할 사진 메타데이터를 유지함. 프로필 삭제는 사진/후보 DB 변경과 함께 롤백되며 실제 파일은 커밋 후에만 지움. 기본 10분마다 30분 지난 사진과 연결 후보, UUID 임시 파일만 정리함. 해당 디렉터리 밖 경로·심볼릭 링크·디렉터리는 삭제하지 않음. `photo.cleanup.enabled=false`로 수동 검증 중 스케줄러를 끌 수 있음.

## 기존 보조 API

기존 `/api/images/pill-photo/identify` 기본 모드와 `/api/images/prescription-photo/identify`는 식별만 하고 즉시 정리하는 무저장 보조 경로임. 낱알 보조 경로에 view=details&profileId를 보내면 v1 정식 흐름으로 연결함. 기존 `/api/images/profiles/{profileId}/photo-identifications`는 업로드 보조 경로임. 새 화면은 위 정식 경로를 사용해야 후보 보존·재조회·선택 저장이 연결됨. 미머지 브랜치에서 추가됐던 `/api/images/profiles/{profileId}/prescription-photo/identify`는 정식 workflow의 호환 별칭임.

## 검증 범위

`PhotoFlowHttpMySqlTests`는 실제 로그인 발급 토큰·HTTP 포트·MySQL·파일을 사용함. 공급자만 로컬 합성 HTTP로 대체함. 후보 재조회·원본 조회·앞뒤 교환·다른 회원 차단·후보 밖 등록 차단·멱등/동시 등록·만료·처리 중 삭제·부분 실패·물리 삭제 실패·롤백·프로필 삭제를 검증함. 로그인→사진→등록→일정→캘린더→채팅→프로필 삭제도 같은 흐름으로 검증함. `PhotoImagePreparationTests`는 실제 PNG·WEBP 디코딩·축소·형식 불일치·손상·용량을 확인함. 스키마 테스트는05 재실행·기존 NULL/코드 보존·잘못된 FK 거절을 확인함.

실제 비전 OCR 정확도·실제 식약처 품목·휴대폰 화면은 이 합성 검증으로 확인했다고 주장하지 않음. 실제 환경에는 OPENAI_API_KEY, 사용 가능한 OPENAI_VISION_MODEL 및 낱알식별03·제품허가08 활용승인이 있는 PUBLIC_DATA_SERVICE_KEY가 필요함. 비밀값은 채팅/Git에 남기지 않고 각 서버 .env에 둠.

이전 REG-002~007 통합 당시 전체 회귀는 **606개·73스위트·실패/오류/생략0·bootJar 성공**임. 원본 develop582개에 사진/공식 후보 검증24개를 추가한 결과이며 [검증 집계](PHOTO_REGISTRATION_VALIDATION.json)에 실행 조건을 기록함.

REG-004 응답 대조 후속: NOT_FOUND에서도 drugs[].readName/lookup을 남겨 이름 검색어로 쓸 수 있게 함. 사진 ID/행/원본은 계속 정리함. 성공 후보와 GET 재조회는 공식 캐시에 저장된 printFront/printBack/shape/color를 함께 제공함. color는 drug의 일반 색상 표현이며 사진 앞뒤의 색으로 임의 분리하지 않음.
