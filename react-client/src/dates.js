// 날짜 계산 도우미. 날짜는 서버와 같은 'YYYY-MM-DD' 글자, 달은 'YYYY-MM' 글자로 다룬다.
// 계산은 UTC 자정 기준으로 해서 브라우저 시간대의 영향을 받지 않는다.

const dayMs = 86400000

function toTime(date) {
  return Date.UTC(Number(date.slice(0, 4)), Number(date.slice(5, 7)) - 1, Number(date.slice(8, 10)))
}

function fromTime(time) {
  return new Date(time).toISOString().slice(0, 10)
}

// 한국 시간 오늘. 서버의 업무 날짜(그만 먹기 · 끝난 일정)와 같은 기준이다.
export function todayKst() {
  return new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Seoul' }).format(new Date())
}

// 실제로 있는 날짜인지('2026-02-30' 은 아님)
export function isDate(value) {
  return typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value) && fromTime(toTime(value)) === value
}

export function isMonth(value) {
  return typeof value === 'string' && /^\d{4}-(0[1-9]|1[0-2])$/.test(value)
}

export function addDays(date, days) {
  return fromTime(toTime(date) + days * dayMs)
}

// from 에서 to 까지 며칠 뒤인지(같은 날 0)
export function daysBetween(from, to) {
  return Math.round((toTime(to) - toTime(from)) / dayMs)
}

// 0 일요일 ~ 6 토요일
export function weekdayOf(date) {
  return new Date(toTime(date)).getUTCDay()
}

export function monthOf(date) {
  return date.slice(0, 7)
}

export function addMonths(month, count) {
  const index = Number(month.slice(0, 4)) * 12 + Number(month.slice(5, 7)) - 1 + count
  return `${Math.floor(index / 12)}-${String((index % 12) + 1).padStart(2, '0')}`
}

// 달력 한 달의 주(일요일 시작). 앞뒤 달의 날짜로 첫 주와 마지막 주를 채운다.
export function monthWeeks(month) {
  const first = `${month}-01`
  const last = addDays(`${addMonths(month, 1)}-01`, -1)
  const start = addDays(first, -weekdayOf(first))
  const end = addDays(last, 6 - weekdayOf(last))
  const weeks = []
  for (let day = start; day <= end; day = addDays(day, 7)) {
    weeks.push(Array.from({ length: 7 }, (_, index) => addDays(day, index)))
  }
  return weeks
}
