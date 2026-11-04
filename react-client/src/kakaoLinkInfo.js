// 카카오톡 연결(DEC-029, PRF-01 카드 · PRF-06)에서 함께 쓰는 글.
import { todayKst } from './dates.js'
import { hourMinute, monthDayTime } from './text.js'

// 한 계정에 연결할 수 있는 카카오톡 수(V3-KAKAO-012)
export const maxKakaoLinks = 5

// 연결 코드가 쓰이는 시간(10분, V3-KAKAO-012)
export const codeLifetimeMs = 10 * 60 * 1000

// 연결마다 카톡에서 지금 보는 프로필 이름. 고르기 전이면 '고르는 중'
export function viewedProfileName(link) {
  return link.currentProfileName ?? '고르는 중'
}

// 마지막 사용 시각: 오늘이면 '15:42', 아니면 '11월 3일 15:42'
export function lastUsedText(dateTime) {
  return dateTime.slice(0, 10) === todayKst() ? hourMinute(dateTime) : monthDayTime(dateTime)
}

// 화면 읽기용 코드: '7K3Q-M9XA' → '연결 코드 7 K 3 Q, M 9 X A'(한 글자씩 읽힌다)
export function spokenCode(code) {
  return `연결 코드 ${code
    .split('-')
    .map((part) => part.split('').join(' '))
    .join(', ')}`
}

// 남은 시간: 581초 → '9분 41초 남음', 40초 → '40초 남음'
export function remainingText(seconds) {
  const minutes = Math.floor(seconds / 60)
  const rest = seconds % 60
  return minutes > 0 ? `${minutes}분 ${rest}초 남음` : `${rest}초 남음`
}
