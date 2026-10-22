import { todayKst } from './dates.js'
import { monthDay, monthDayTime } from './text.js'

// 챗봇 화면 글자와 답변 본문 · 근거 나누기. 목업 CHT-01 · 02 기준.

// 질문 글자 수 한도(서버 QuestionService 와 같다)
export const questionLimit = 1000

// 서버가 답변 content 끝에 붙이는 근거 꼬리표(AnswerSources.MARKER). 다음 줄에 근거 목록 JSON 배열이 온다.
const sourcesMarker = '@@SOURCES@@'

// 근거 출처(AnswerSource.Type). USER_INPUT 은 내가 입력한 복용 정보다.
export const sourceLabels = { EASY_DRUG: 'e약은요', PERMIT: '의약품 허가정보', DUR: 'DUR 공공데이터' }

function isSource(item) {
  return (
    typeof item === 'object' &&
    item !== null &&
    (Object.hasOwn(sourceLabels, item.source) || item.source === 'USER_INPUT') &&
    typeof item.drugName === 'string' &&
    item.drugName.trim() !== ''
  )
}

// 답변 content 를 본문과 근거로 나눈다. 꼬리표가 없으면 함께 저장된 근거가 없는 답변이다.
// sources: 근거 배열 · 'broken'(꼬리표는 있는데 근거를 읽을 수 없음, 목업 "근거 정보 손상")
export function splitAnswer(content) {
  const index = content.indexOf(sourcesMarker)
  if (index < 0) return { body: content.trim(), sources: [] }
  let sources
  try {
    const list = JSON.parse(content.slice(index + sourcesMarker.length))
    sources = Array.isArray(list) && list.every(isSource) ? list : 'broken'
  } catch {
    sources = 'broken'
  }
  return { body: content.slice(0, index).trim(), sources }
}

// 금기 정보 종류(DurLookup.Type). tone 은 카드 색이다(목업 노인주의만 주의, 나머지는 위험).
export const durTypes = {
  INTERACTION: {
    label: '병용금기',
    title: '같이 먹으면 안 돼요',
    tone: 'negative',
    closing: '함께 드시기 전에 의사·약사에게 확인해 주세요.',
  },
  AGE: { label: '연령금기', title: '연령금기 정보가 있어요', tone: 'negative', closing: '의사·약사에게 확인해 주세요.' },
  ELDERLY: { label: '노인주의', title: '노인주의 정보가 있어요', tone: 'caution', closing: '의사·약사에게 확인해 주세요.' },
  PREGNANCY: { label: '임부금기', title: '임부금기 정보가 있어요', tone: 'negative', closing: '의사·약사에게 확인해 주세요.' },
}

// 추천 질문(목업 CHT-01). 목업의 약 이름이 든 두 질문은 사람마다 약이 달라 넣지 않았다.
export const exampleQuestions = ['오늘 저녁에 먹을 약이 뭐야?', '지금 먹는 약끼리 같이 먹어도 돼?']

// 대화방 목록 시각: 오늘이면 '오늘 09:30', 아니면 '10월 16일'(목업 CHT-01)
export function roomTime(dateTime) {
  return dateTime.slice(0, 10) === todayKst() ? `오늘 ${dateTime.slice(11, 16)}` : monthDay(dateTime)
}

// 질문 · 답변 시각: 오늘이면 '오늘 09:42', 아니면 '10월 16일 09:10'(목업 CHT-02)
export function messageTime(dateTime) {
  return dateTime.slice(0, 10) === todayKst() ? `오늘 ${dateTime.slice(11, 16)}` : monthDayTime(dateTime)
}

// 질문을 보낸 때. at: 한국 시각 'YYYY-MM-DDTHH:mm:ss'(서버가 질문 시각을 돌려주지 않아 보낸 질문의 시각으로 쓴다)
// ms: 보낸 뒤 지난 시간을 잴 때 쓰는 밀리초
export function sendTime() {
  const now = new Date()
  const at = new Intl.DateTimeFormat('sv-SE', {
    timeZone: 'Asia/Seoul',
    dateStyle: 'short',
    timeStyle: 'medium',
  }).format(now)
  return { at: at.replace(' ', 'T'), ms: now.getTime() }
}
