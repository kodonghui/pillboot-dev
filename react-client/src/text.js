import { weekdayOf } from './dates.js'

// 화면 글자 도우미: 조사와 날짜 · 시각 표기.
// 서버 날짜(YYYY-MM-DD)와 시각(YYYY-MM-DDTHH:mm:ss)은 한국 시간 값이라 시간대 계산 없이 글자로 나눠 쓴다.

// 끝 글자의 받침 번호. 한글이 아니면 null, 받침이 없으면 0, ㄹ 받침은 8
function finalConsonant(word) {
  const code = word.charCodeAt(word.length - 1) - 0xac00
  if (Number.isNaN(code) || code < 0 || code > 11171) return null
  return code % 28
}

// 조사 "로/으로". 받침이 있으면(ㄹ 받침 제외) "으로"다.
export function roAfter(word) {
  const final = finalConsonant(word)
  if (final === null) return '(으)로'
  return final === 0 || final === 8 ? '로' : '으로'
}

// 조사 "을/를". 받침이 있으면 "을"이다.
export function eulAfter(word) {
  const final = finalConsonant(word)
  if (final === null) return '을(를)'
  return final === 0 ? '를' : '을'
}

// 조사 "은/는". 받침이 있으면 "은"이다.
export function eunAfter(word) {
  const final = finalConsonant(word)
  if (final === null) return '은(는)'
  return final === 0 ? '는' : '은'
}

// 조사 "이/가". 받침이 있으면 "이"다.
export function iAfter(word) {
  const final = finalConsonant(word)
  if (final === null) return '이(가)'
  return final === 0 ? '가' : '이'
}

// 서술격 조사 "이에요/예요". 받침이 있으면 "이에요"다.
export function ieyoAfter(word) {
  const final = finalConsonant(word)
  if (final === null) return '(이)에요'
  return final === 0 ? '예요' : '이에요'
}

const weekdayNames = ['일', '월', '화', '수', '목', '금', '토']

function weekdayName(date) {
  return weekdayNames[weekdayOf(date.slice(0, 10))]
}

// '2026-10-20' → '10월 20일'
export function monthDay(date) {
  const [, month, day] = date.slice(0, 10).split('-')
  return `${Number(month)}월 ${Number(day)}일`
}

// '2026-10-22' → '2026년 10월 22일(목)'
export function fullDate(date) {
  return `${date.slice(0, 4)}년 ${monthDay(date)}(${weekdayName(date)})`
}

// '2026-10-22' → '10월 22일 목요일'
export function dayName(date) {
  return `${monthDay(date)} ${weekdayName(date)}요일`
}

// '2026-10' → '2026년 10월'
export function monthTitle(month) {
  return `${month.slice(0, 4)}년 ${Number(month.slice(5, 7))}월`
}

// 일정 기간: '10월 14일부터 11월 12일까지'
export function periodText(startDate, endDate) {
  return `${monthDay(startDate)}부터 ${monthDay(endDate)}까지`
}

// '2026-10-20T10:12:30' → '10월 20일 10:12'
export function monthDayTime(dateTime) {
  return `${monthDay(dateTime)} ${dateTime.slice(11, 16)}`
}

// '2026-09-30' → '2026.09.30'
export function dotDate(date) {
  return date.slice(0, 10).replaceAll('-', '.')
}
