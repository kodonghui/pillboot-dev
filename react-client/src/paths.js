// 화면 주소. 주석의 화면 ID는 목업 v2 보드 기준이다.
// ':' 가 있는 주소는 generatePath(paths.이름, { 값 }) 으로 링크를 만든다.
export const paths = {
  welcome: '/welcome', // MEM-01 시작 화면
  signup: '/signup', // MEM-02 회원가입 약관 동의
  signupInfo: '/signup/info', // MEM-03 회원가입 정보 입력
  login: '/login', // MEM-04 로그인
  sessionExpired: '/session-expired', // CMN-03 로그인 만료
  home: '/', // CMN-01 홈
  drugs: '/drugs', // MED-01 약 목록
  drugNew: '/drugs/new', // MED-02 약 등록 방법 고르기
  drugSearch: '/drugs/search', // MED-03 약 이름 검색(?q= 검색어, 처방전에서 오면 prescriptionSearchLink)
  drugDetail: '/drugs/:myDrugId', // MED-04 약 상세
  prescriptionPhoto: '/drugs/new/prescription', // IMG-01 처방전 · 약봉투 사진 올리기(찾은 결과는 prescriptionResultLink)
  pillPhoto: '/drugs/new/pill', // IMG-02 알약 사진 올리기
  calendar: '/calendar', // SCH-01 복용 달력
  scheduleNew: '/schedules/new', // SCH-03 일정 추가(scheduleLink 로 만든다)
  scheduleEdit: '/schedules/edit', // SCH-06 일정 수정(scheduleLink 로 만든다)
  chat: '/chat', // CHT-01 챗봇 대화방 목록
  chatRoom: '/chat/:sessionId', // CHT-02 대화방
  profiles: '/profiles', // PRF-01 프로필 관리
  profileNew: '/profiles/new', // PRF-02 프로필 만들기
  profileEdit: '/profiles/:profileId/edit', // PRF-03 프로필 수정
  notifications: '/notifications', // NTF-01 알림 설정
  doses: '/doses', // NTF-02 그 시각에 먹을 약(?date=YYYY-MM-DD&time=HH:mm, 복약 알림을 누르면 열린다)
}

// 일정 추가 · 수정 주소. 사람 · 약 · 날짜와 들어온 곳(from: home · drugs · drug · calendar)을 쿼리로 넘겨
// 새로고침해도 같은 화면이 열리고, 저장 · 취소 뒤 들어온 곳으로 돌아갈 수 있게 한다.
export function scheduleLink(path, { profileId, myDrugId, date, from }) {
  const query = new URLSearchParams()
  if (profileId) query.set('profile', profileId)
  if (myDrugId) query.set('drug', myDrugId)
  if (date) query.set('date', date)
  if (from) query.set('from', from)
  const search = query.toString()
  return search ? `${path}?${search}` : path
}

// 처방전 · 약봉투 찾은 약 확인(IMG-04 · 06) 주소. 사진이 남은 결과는 사진 번호(photo)로 새로고침해도 후보를 다시 불러오고,
// 읽은 이름만 남은 결과(모두 0건이라 사진이 없음)는 names 로 표시만 해 둔다.
export function prescriptionResultLink(photoId) {
  return `${paths.prescriptionPhoto}?${new URLSearchParams(photoId ? { photo: photoId } : { names: '1' })}`
}

// 처방전 결과에서 이름 고쳐 검색(MED-03). 읽은 이름을 검색어(q)로 넣고, 고른 약이 들어갈 자리(pick: readNo)와
// 돌아갈 결과의 사진 번호(photo)를 넘긴다.
export function prescriptionSearchLink(group, photoId) {
  const query = { q: group.readName, pick: group.readNo }
  if (photoId) query.photo = photoId
  return `${paths.drugSearch}?${new URLSearchParams(query)}`
}
