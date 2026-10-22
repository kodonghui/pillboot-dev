// 화면 주소. 주석의 화면 ID는 목업 v2 보드 기준이다.
export const paths = {
  welcome: '/welcome', // MEM-01 시작 화면
  signup: '/signup', // MEM-02 회원가입 약관 동의
  signupInfo: '/signup/info', // MEM-03 회원가입 정보 입력
  login: '/login', // MEM-04 로그인
  sessionExpired: '/session-expired', // CMN-03 로그인 만료
  home: '/', // CMN-01 홈
  drugs: '/drugs', // MED-01 약 목록
  calendar: '/calendar', // SCH-01 복용 달력
  chat: '/chat', // CHT-01 챗봇 대화방 목록
  profiles: '/profiles', // PRF-01 프로필 관리
  profileNew: '/profiles/new', // PRF-02 프로필 만들기
  notifications: '/notifications', // NTF-01 알림 설정
}
