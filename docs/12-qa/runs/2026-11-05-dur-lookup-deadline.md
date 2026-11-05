# 실행 2026-11-05 병용금기 조회가 끝나지 못하는 문제(DEC-034)

- 왜: 운영에서 영상 흐름을 걷던 발표 준비 중 찾음. 에어탈정·케토라신정·노바스크정을 넣고 "같이 먹어도 돼?"로 물으면 병용금기 조회 6건 중 2건만 성공하고 금기 카드가 뜨지 않으며 답이 약 50초 걸렸음.
- 범위: DEC-034 '영상 흐름의 막히는 곳을 고침'에 따름. 서버 `DurLookupService`만 고침. JSON 계약·스키마·SQL은 그대로임.

## 다음 원인: 겹친 행(운영 기록으로 확인함)

위 고침 뒤에도 케토라신정이 '확인하지 못한 약'으로 남았음. 처음에는 시간이 모자란 것으로 짐작했으나 틀렸음. 진단 기록(PR #58)을 운영에 올리고 2026-11-05 11:15 KST에 '아버지(가상)'으로 "같이 먹어도 돼?"를 물어 아래 기록을 얻음.

```text
type=INTERACTION result=duplicate_rows repeatedRows=1 repeatedRawRows=0 samePage=1 otherPage=0 differingFields=[MIXTURE_INGR_ENG_NAME, MIXTURE_INGR_KOR_NAME] otherDifferingFields=0
type=INTERACTION result=unavailable reason=duplicate_rows totalRows=1430 elapsedMs=14314
```

- 1,430행을 14.3초에 다 읽었고, 마감 30초 전에 끝남. 시간 문제가 아니었음.
- 같은 페이지의 두 행이 상대 약의 성분 표기 칸만 다름. 서버가 읽는 칸(상대 품목, 성분, 분류, 내용, 갱신일)은 같아 중복 검사에 걸렸고, 행 하나 때문에 1,430행 전체가 실패가 됨.
- 원본까지 같은 행은 0건이라 페이지 반복의 흔적은 없음.

고침: 원본 행이 똑같이 또 오면 지금처럼 실패로 둠. 원본은 다르고 읽는 칸만 같으면 같은 금기이므로 하나로 합치고 `result=merged_rows`를 남김. 일부 페이지만 읽은 결과를 성공으로 쓰지 않는 규칙은 그대로임. JSON 계약·스키마·SQL은 바꾸지 않음.

## 앞선 원인: 조회 순서와 마감(코드로 확인함)

| 차례 | 내용 |
| --- | --- |
| 1 | 품목×유형 조회를 4건씩 묶어 앞 묶음이 다 끝나야 다음 묶음을 시작했음 |
| 2 | 묶음들이 마감 30초 하나를 함께 썼음 |
| 3 | 한 유형의 페이지를 하나씩 차례로 읽었음. 운영에서 전해 받은 수로는 케토라신정 병용 행이 1,430건이라 15페이지임 |
| 4 | 그래서 앞 묶음이 마감을 다 쓰면 뒤 묶음의 품목은 부르지도 못하고 실패가 됨. 영상의 약 순서에서는 짝을 잡을 수 있는 에어탈정(운영에서 전해 받은 수로 21행)이 뒤 묶음이었음 |

전해 받은 수와 약 순서는 운영에서 본 것이고, 이 기록을 쓸 때 공공데이터를 직접 다시 부르지는 않았음. 실제 키는 운영 설정에만 있음.

## 고침

| 고침 | 내용 |
| --- | --- |
| 묶음 대기 제거 | 조회를 한꺼번에 시작하고 자리(화면 조회 4, 등록 후 선조회 2)만 제한함. 자리는 먼저 온 차례대로 줌. 첫 페이지들이 먼저 줄을 서므로 짧은 품목이 긴 품목 뒤로 밀리지 않음 |
| 남은 페이지 병렬 | 첫 페이지로 전체 수를 안 뒤 남은 페이지를 자리 안에서 함께 읽음. 행 순서는 페이지 순으로 합침. 전체 수·페이지 번호·행 수·중복 검사와 '모든 페이지가 맞아야 성공' 규칙은 그대로임 |
| 끝난 결과 보존 | 앞 품목을 기다리다 마감이 지나면 이미 끝난 뒤 품목까지 실패로 쳤음. 코드를 읽다 찾음. 끝난 조회의 결과는 마감 뒤에도 씀 |
| 어긋난 페이지 | 한 페이지가 어긋나면 남은 페이지를 더 부르지 않고 그 유형을 실패로 둠 |

'한쪽만 성공해도 짝이 잡히면 카드 표시'는 `DurEvaluator`에 이미 있었음(`aVerifiedDirectionFindsARegisteredPairEvenWhenTheOtherDrugLookupFails`). 일부 페이지만 읽은 결과는 지금도 성공으로 쓰지 않음. 다 읽지 못한 약은 `uncheckedDrugNames`로 남아 '일부 약·유형은 확인하지 못했어요'가 나옴.

## 확인

| 시험(`DurExternalTests`, 로컬 합성 HTTP) | 고치기 전 코드 | 고친 코드 |
| --- | --- | --- |
| `aLongPartnerListDoesNotStopTheNextDrugsLookupOrHideAConfirmedPair` 긴 약이 맨 앞인 약 셋, 마감 1초 | 실패 | 통과. `FOUND`, `PARTIAL`, 미확인 약에 긴 약 |
| `remainingPagesAreReadTogetherWithinTheSlotsAndKeepTheirOrder` 15페이지 | 실패 | 통과. 1,430행, 순서 유지, 동시 호출 2~4 |
| `backgroundPrefetchKeepsItsTwoSlotsWhileReadingManyPages` | 시험에서 통과 | 통과. 동시 호출 2 이하 |
| `aBadMiddlePageFailsTheWholeTypeAndStopsReadingTheRest` | 시험에서 통과 | 통과 |

## 보지 못한 것

- 운영 공공데이터가 동시 4건에서 내는 실제 속도와 답까지 걸리는 시간. 시험의 지연은 합성값임. 50초가 얼마나 줄었는지는 운영에서 다시 걸어 봐야 앎.
- 실제 에어탈정 응답에 케토라신정 품목코드가 짝으로 들어 있는지. 운영에서 금기 카드가 뜨는지로 확인해야 함.

## 수

- `./gradlew clean test bootJar` 성공: 시험 1205개, 실패 0, 건너뜀 228(JDK 21, 결과 XML을 합함). 건너뜀에는 로컬에서 돌지 않는 MySQL 시험이 들어 있고, 그 시험은 PR CI에서 돎.
- `npm run lint` 오류 0, `npm run build` 성공.
