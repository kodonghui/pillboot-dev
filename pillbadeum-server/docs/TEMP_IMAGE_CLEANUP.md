# 임시 이미지 삭제 (REQ-18)

## 범위

현재 이미지 처리에서 서버가 저장한 UUID 파일명 목록만 전달받아 `img_temp`의 해당 파일을 삭제한다.
사진 원본을 DB에 넣거나, 새 테이블/컬럼·삭제 API·정리 스케줄러를 추가하지 않는다.

- `TemporaryPhotoUploadService`: 저장 성공 때는 파일을 다음 단계에 넘기기 위해 유지한다.
  두 번째/세 번째 파일 검증·저장에 실패하면 앞서 저장한 이번 요청의 파일만 즉시 정리한다.
- `FileStorage`: `transferTo`가 파일을 일부 쓴 뒤 실패한 경우, 아직 호출자에게 반환하지 못한 파일도 정리한다.
- `PillPhotoIdentificationService`, `PrescriptionPhotoIdentificationService`: 저장·읽기·정리에 공통 `img_temp`를 사용한다.
  현재 DB 쓰기가 없는 식별 경로에서는 결과 작성 후 파일을 삭제하고, 식별 실패·시간 초과 때도 이번 요청의 파일을 정리한다.
  바깥의 일반 Spring 저장 트랜잭션 안에서 완료한 식별은 커밋 후 삭제하고, 롤백하면 파일을 보존한다.
- 파일 삭제 실패는 로그에 수량과 예외 종류만 남긴다. 다른 파일 정리를 계속하고 식별/저장 결과나 원래 예외를 바꾸지 않는다.

## 사진 저장 코드와 연결

`TemporaryPhotoCleanupService`는 내부 서비스용이다. 클라이언트가 보낸 파일명을 그대로 넘기지 않는다.
해당 요청의 업로드 결과 또는 소유권을 확인한 서버의 사진 기록에서 파일명을 얻어야 한다.

저장 트랜잭션 **안**에서 식별 정보 저장을 끝낸 직후:

```java
// @Transactional 메서드 안. 아래 이름은 사용 예시이며 새로운 저장 메서드를 구현한 것이 아니다.
identificationRepository.saveAll(results);
cleanup.deleteAfterCommit(savedNames);
```

`deleteAfterCommit`는 실제 트랜잭션과 커밋 콜백이 모두 있어야 등록한다. 파일명 목록을 복사해 두고
커밋 성공 때만 삭제한다. 롤백·커밋 실패·커밋 콜백 없는 호출에서는 파일을 보존한다.
저장 트랜잭션 서비스가 **반환해서 커밋까지 끝난 뒤** 호출한다면 `cleanup.deleteNow(savedNames)`를 사용한다.
`deleteAfterProcessing`는 현재 DB 저장이 없는 식별 경로의 완료 처리용이다.
DB 쓰기를 추가할 때는 저장 TX 안의 `deleteAfterCommit` 호출로 완료 경계를 명시한다.

현재 원격 코드에는 `photo`/`ocr_candidate`를 저장하는 구현이나 이 기록과 `img_temp` 파일을 연결하는 흐름이 없다.
처방전 업로드 API는 아직 `TemporaryPhotoUploadService`만 호출한다. 위 삭제 서비스를 붙였다고 해서
해당 업로드 API에서 OCR·DB 저장·저장 후 삭제까지 구현된 것은 아니다.
이 저장 연결은 사진 저장 코드가 추가될 때 함께 연결해야 한다. `photo`/`ocr_candidate` 행 삭제도 이번 파일 삭제 서비스의 역할이 아니다.

## 삭제 제한

- 업로드 기본 경로의 `img_temp` 직계 파일만 삭제한다. 폴더 전체를 탐색하거나 재귀 삭제하지 않는다.
- `FileStorage.saveImage`가 생성하는 소문자 UUID 32자리 + `.jpg`/`.png`/`.webp`만 받는다.
- 상대/절대 경로, 디렉터리, 심볼릭 링크, 다른 폴더를 가리키는 `img_temp`는 삭제하지 않는다.
- 이미 없는 파일은 정상적인 중복 정리로 처리한다. 다른 요청 파일과 `profiles`/`pills`/`prescriptions`는 유지한다.
- 이전 코드가 `pills`/`prescriptions`에 남긴 파일이나 실패한 삭제를 주기적으로 청소하는 작업은 포함하지 않는다.

## 설계 문서와의 차이

이번 동작은 팀 설계(식별 결과 저장 후 파일 삭제)에 맞춘다.
현재 노션 REG-004/005/007의 원문은 알약 후보 확인에서 서버 원본을 비교한 후 최종 약 등록이 끝나면 삭제하도록 되어 있다.
그 화면을 유지한다면 성공 삭제 호출을 후보 저장 뒤가 아니라 최종 등록 커밋 뒤로 옮겨야 한다.
별도 REQ-18 삭제 작업 페이지에는 아직 상세 조건이 없다. 구현에 맞는 설계 문서 정리는 별도 협의 대상이다.

## 검증

```powershell
.\gradlew.bat --offline test --tests '*TemporaryPhotoCleanupServiceTests' --tests '*TemporaryPhotoFlowTests'
```

테스트는 `@TempDir`의 합성 파일만 사용하고 AI·공공데이터는 모의 응답을 사용한다. 실제 업로드·업무 DB·유료 API는 사용하지 않는다.
실제 Spring 트랜잭션 동기화 생명주기로 커밋/롤백 순서를 검사하며 DB 저장 자체는 검증 범위에 포함하지 않는다.
심볼릭 링크 생성 권한이 없는 OS에서는 링크 2개 검사만 생략되고, 해당 검사는 Linux CI에서 실행할 수 있다.

2026-10-20 로컬 검증: 이미지 관련 20개 중 18개 통과, 링크 생성 권한 때문에 2개 생략, 실패 0.
전체 `test bootJar`는 490개 중 388개 통과, 전용 DB 등 환경 조건 100개와 링크 2개 생략, 실패/오류 0, 빌드 성공.
