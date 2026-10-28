# PillSoGood · 필받음

사진·이름 검색으로 본인과 가족의 약을 등록하고, 공식 의약품 근거를 확인하며 AI에게 질문하는 웹 서비스임.

현재 작업 저장소는 이 저장소임.

## 팀

| 구성원 | 역할 |
| --- | --- |
| 고동희 | PM · 제품·AI 상담·공통 정책 |
| 팀원 | DB·프론트 리드 |
| 팀원 | 백엔드 리드 |

세 명 모두 개발에 참여함.

## 구성과 실행

- 서버는 `pillbadeum-server/`의 Java 21·Spring Boot 4.1.1·MySQL 기반임.
- 클라이언트는 `react-client/`의 React·Vite 기반임.
- 환경값은 서버의 `.env.example`을 참고해 별도 `.env`에 설정함.
- 신규 DB 설치 순서는 `00 → 01 → 13`이며, `02`는 선택 샘플임. 기존 DB는 적용 이력을 확인해 갱신함.

[서버 실행·설정](pillbadeum-server/README.md) · [DB 설치·갱신](pillbadeum-server/sql/README.md) · [서버 패키지](pillbadeum-server/docs/PACKAGE_GUIDE.md)

서버 실행 명령은 다음과 같음.

```bash
cd pillbadeum-server
./gradlew bootRun
```

클라이언트 실행 명령은 다음과 같음.

```bash
cd react-client
npm ci
npm run dev
```

## 확인 명령

프론트 변경 시 `react-client/`에서 `npm run lint`·`npm run build`를 실행함. Live 상태 처리의 별도 회귀 확인은 저장소 루트에서 다음 명령으로 수행함.

```bash
node --experimental-test-module-mocks --test react-client/scripts/live-conversation.test.mjs
```

## 이관 기준

- 최초 이관 코드는 2026-10-26 최종 웹 배포 보관 커밋 `733699cb10ca446309b8ff55ff8b691ea584e41c`임. 기존 Git 이력을 함께 보존함.
- 개인 `develop` 보관본에 없던 Live 음성의 여러 약·전체 약 일정 등록, 일괄 저장, 자막·진행·완료 표시 보완을 포함함.
- 위 기록은 보관본의 출처임. 현재 운영 서버를 직접 조회해 실행 버전을 재검증했다는 뜻은 아님.

