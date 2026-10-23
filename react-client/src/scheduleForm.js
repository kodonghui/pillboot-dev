// 일정 추가 · 수정 입력값(목업 SCH-03 · 06)과 저장 요청. 서버 규칙: 복용 시각 1~3개, 1회 개수 1~10, 복용 때 20자까지.
import { defaultSlots, normalizeTime, slotsOf } from './scheduleInfo.js'

export const timingLimit = 20
export const doseMin = 1
export const doseMax = 10
export const timingWords = ['식전', '식후 30분', '식후 바로', '자기 전']

// 약에 저장된 값으로 채운 복용 시간 · 1회 개수 · 복용 때. 일정이 있으면 그 시간을, 없으면 아침 · 점심 · 저녁 기본 시각을 넣는다.
// drug 는 일정 고르기 목록(ScheduleResponse) 또는 내 약 상세 응답이다. 없으면 기본값이다.
export function drugValues(drug) {
  return {
    slots: drug?.doseTimes?.length ? slotsOf(drug.doseTimes) : defaultSlots(),
    dose: String(drug?.dosePerTime ?? doseMin),
    timing: drug?.timing ?? '',
  }
}

export function initialForm(startDate, drug) {
  return { startDate, endDate: '', ...drugValues(drug) }
}

// 1회 개수 글자가 1~10 의 정수인지
export function validDose(text) {
  return /^\d+$/.test(text.trim()) && Number(text) >= doseMin && Number(text) <= doseMax
}

// 복용 시간 오류와 표시할 칸. 모양이 틀린 칸이 먼저, 그다음 같은 시각이 두 번 들어간 뒤쪽 칸이다.
function timesError(slots) {
  const on = slots.map((slot, index) => ({ ...slot, index })).filter((slot) => slot.on)
  if (on.length === 0) return { message: '복용 시간을 하나 이상 정해 주세요.', slots: [] }
  const malformed = on.filter((slot) => !normalizeTime(slot.time))
  if (malformed.length) return { message: '시간을 07:30처럼 써 주세요.', slots: malformed.map((slot) => slot.index) }
  const seen = new Set()
  const repeated = []
  for (const slot of on) {
    const time = normalizeTime(slot.time)
    if (seen.has(time)) repeated.push(slot.index)
    seen.add(time)
  }
  if (repeated.length) return { message: '같은 시간이 두 번 들어 있어요. 다른 시간으로 바꾸거나 빼 주세요.', slots: repeated }
  return null
}

// 고칠 곳. 키 순서가 화면 순서(약 → 종료일 → 복용 시간 → 1회 개수)이고, 저장 실패 요약의 이름 순서다.
// drugMissing: 일정 추가에서 약을 고르지 않았음(수정은 약이 정해져 있다)
export function checkForm(form, { drugMissing }) {
  const errors = {}
  if (drugMissing) errors.drug = { message: '약을 골라 주세요.' }
  if (!form.endDate) errors.endDate = { message: '종료일을 골라 주세요.' }
  const times = timesError(form.slots)
  if (times) errors.times = times
  if (!validDose(form.dose)) errors.dose = { message: '1부터 10까지 넣어 주세요.' }
  return errors
}

// 일정 추가에서 고른 약의 이름. 내 약(일정 고르기 응답)은 drugName, 공식 약(검색 결과)은 name 이다.
export function pickName(pick) {
  return pick.kind === 'mine' ? pick.drug.drugName : pick.drug.name
}

export const errorNames = { drug: '약', endDate: '종료일', times: '복용 시간', dose: '1회 개수' }

// PUT .../schedule 요청. 시각은 'HH:mm' 오름차순, 복용 때를 비우면 보내지 않아 지운다(서버 규칙).
export function requestOf(form) {
  const request = {
    startDate: form.startDate,
    endDate: form.endDate,
    doseTimes: form.slots
      .filter((slot) => slot.on)
      .map((slot) => normalizeTime(slot.time))
      .sort(),
    dosePerTime: Number(form.dose),
  }
  const timing = form.timing.trim()
  if (timing) request.timing = timing
  return request
}
