// 복약 일정 응답을 화면에 맞게 바꾸는 도우미. 목업 SCH-01 · 03 · 05 · 06 기준.
import { daysBetween } from './dates.js'

// 일정 하나를 가리키는 글자(프로필 번호-약 번호). 달력 막대와 열린 상세가 같은 일정인지 볼 때 쓴다.
export function scheduleKey(profileId, myDrugId) {
  return `${profileId}-${myDrugId}`
}

// 끝난 일정(종료일이 오늘 전) · 아직 시작 전(시작일이 오늘 뒤) · 진행 중. 서버 그만 먹기(409) 규칙과 같다.
export function scheduleState(schedule, today) {
  if (schedule.endDate < today) return 'ended'
  if (schedule.startDate > today) return 'notStarted'
  return 'active'
}

// 1회 개수. 서버에 약 단위(정 · 캡슐)가 없어 목업의 "1정" 대신 "1개"로 쓴다.
export function doseText(dosePerTime) {
  return `${dosePerTime}개`
}

// 한 주(일~토 7일)에 걸치는 일정 막대. 시작일이 이른 일정부터, 같으면 서버 순서(프로필 · 약 번호)대로
// 빈 줄의 맨 위에 놓는다(목업 .l1~.l5). 주를 넘어 이어지면 continuesLeft · continuesRight 가 붙는다.
export function weekBars(schedules, week) {
  const weekStart = week[0]
  const weekEnd = week[6]
  const bars = schedules
    .map((schedule, order) => ({ schedule, order }))
    .filter(({ schedule }) => schedule.startDate <= weekEnd && schedule.endDate >= weekStart)
    .sort((a, b) => a.schedule.startDate.localeCompare(b.schedule.startDate) || a.order - b.order)
    .map(({ schedule }) => ({
      schedule,
      from: daysBetween(weekStart, schedule.startDate > weekStart ? schedule.startDate : weekStart),
      to: daysBetween(weekStart, schedule.endDate < weekEnd ? schedule.endDate : weekEnd),
      continuesLeft: schedule.startDate < weekStart,
      continuesRight: schedule.endDate > weekEnd,
    }))
  const laneEnds = []
  for (const bar of bars) {
    let lane = laneEnds.findIndex((end) => end < bar.from)
    if (lane < 0) lane = laneEnds.length
    laneEnds[lane] = bar.to
    bar.lane = lane
  }
  return { bars, lanes: laneEnds.length }
}

// 복용 시간 칸(목업 SCH-03 · 06): 아침 · 점심 · 저녁 세 칸과 기본 시각. 칸을 빼고 다시 더할 수 있다.
export const doseSlots = [
  { name: '아침', time: '07:00' },
  { name: '점심', time: '11:00' },
  { name: '저녁', time: '17:00' },
]

function minutesOf(time) {
  return Number(time.slice(0, 2)) * 60 + Number(time.slice(3, 5))
}

// 고를 수 있는 칸 번호 묶음(오름차순): 시각이 2개면 [0,1] · [0,2] · [1,2]
function slotChoices(count, from = 0) {
  if (count === 0) return [[]]
  const choices = []
  for (let slot = from; slot <= doseSlots.length - count; slot += 1) {
    for (const rest of slotChoices(count - 1, slot + 1)) choices.push([slot, ...rest])
  }
  return choices
}

// 저장된 복용 시각을 칸에 나눠 담는다. 순서를 지키면서 기본 시각과 가장 가까운 칸을 고른다(17:00 하나 → 저녁).
// 결과는 칸마다 { on, time } 이다.
export function slotsOf(doseTimes) {
  const times = [...doseTimes].sort()
  let best = null
  for (const choice of slotChoices(times.length)) {
    const cost = choice.reduce((sum, slot, index) => sum + Math.abs(minutesOf(times[index]) - minutesOf(doseSlots[slot].time)), 0)
    if (!best || cost < best.cost) best = { choice, cost }
  }
  return doseSlots.map((slot, index) => {
    const at = best.choice.indexOf(index)
    return at < 0 ? { on: false, time: slot.time } : { on: true, time: times[at] }
  })
}

export function defaultSlots() {
  return doseSlots.map((slot) => ({ on: true, time: slot.time }))
}

// 'HH:mm' 으로 맞춘 시각. '7:5' 처럼 모양이 틀리면 null
export function normalizeTime(text) {
  const match = text.trim().match(/^(\d{1,2}):(\d{2})$/)
  if (!match || Number(match[1]) > 23 || Number(match[2]) > 59) return null
  return `${match[1].padStart(2, '0')}:${match[2]}`
}
