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

// 근거를 공식 자료와 내가 입력한 복용 기록(USER_INPUT)으로 나눈다. 두 쪽 날짜는 뜻이 달라 섞지 않고,
// 순서도 서버가 붙인 순서 그대로 두며 날짜로 다시 정렬하지 않는다.
// 공식 자료는 품목코드(없으면 약 이름)가 같은 것끼리 한 약으로 묶는다.
// official: [{ key, drugName, sources }] · mine: USER_INPUT 근거 배열
export function sourceGroups(sources) {
  const official = []
  const drugs = new Map()
  const mine = []
  for (const source of sources) {
    if (source.source === 'USER_INPUT') {
      mine.push(source)
      continue
    }
    const seq = typeof source.itemSeq === 'string' ? source.itemSeq.trim() : ''
    const key = seq ? `seq:${seq}` : `name:${source.drugName.trim()}`
    let drug = drugs.get(key)
    if (!drug) {
      drug = { key, drugName: source.drugName, sources: [] }
      drugs.set(key, drug)
      official.push(drug)
    }
    drug.sources.push(source)
  }
  return { official, mine }
}

// 답변 본문의 서식. 서버 답변 지침이 형식을 정하지 않아 모델이 마크다운 일부를 섞어 쓴다.
// 문단 · 줄바꿈 · 글머리 목록(- * + •) · 번호 목록(1. · 1)) · **굵게** · 표를 읽고, 나머지는 글자로 둔다.
// HTML 로 바꾸지 않고 글자 조각으로만 나눈다. 화면은 조각을 React 글자로 넣으므로 본문에 든 태그는 실행되지 않는다.
const bulletLine = /^(\s*)[-*+•]\s+(\S.*)$/
const numberLine = /^(\s*)(\d{1,3})[.)]\s+(\S.*)$/
const boldText = /\*\*(\S(?:.*?\S)?)\*\*/g

// 줄 앞 빈칸 너비(탭은 4칸)
const indentOf = (space) => space.replace(/\t/g, '    ').length

function listLine(line) {
  const bullet = bulletLine.exec(line)
  if (bullet) return { indent: indentOf(bullet[1]), ordered: false, start: 1, text: bullet[2].trim() }
  const number = numberLine.exec(line)
  if (number) return { indent: indentOf(number[1]), ordered: true, start: Number(number[2]), text: number[3].trim() }
  return null
}

// 제목과 구분선이 확인된 표만 해석함. 이스케이프한 \|는 셀 안의 글자로 유지함.
function tableCells(line) {
  const value = line.trim()
  if (!/(?<!\\)\|/.test(value)) return null
  return value.replace(/^\|/, '').replace(/(?<!\\)\|$/, '').split(/(?<!\\)\|/)
    .map((cell) => cell.trim().replace(/\\\|/g, '|'))
}

// 답변 본문을 문단과 목록으로 나눈다. 빈 줄은 문단 · 목록을 끝내고, 더 들여 쓴 항목은 앞 항목 아래 목록이 된다.
// 목록 항목보다 들여 쓴 일반 줄은 그 항목에 이어 쓴다. 번호 목록은 첫 항목의 번호(start)부터 센다.
// block: { kind: 'paragraph', lines } · { kind: 'list', ordered, start, indent, items: [{ lines, lists }] }
export function answerBlocks(body) {
  const blocks = []
  let paragraph = null
  // 지금 이어 쓰는 목록들. 뒤로 갈수록 더 들여 쓴 아래 목록이다.
  let open = []
  const lines = body.split(/\r?\n/)
  for (let index = 0; index < lines.length; index += 1) {
    const line = lines[index]
    if (line.trim() === '') {
      paragraph = null
      open = []
      continue
    }
    const header = tableCells(line)
    const divider = index + 1 < lines.length ? tableCells(lines[index + 1]) : null
    if (header && divider?.length === header.length && divider.every((cell) => /^:?-{3,}:?$/.test(cell))) {
      const rows = []
      index += 1
      while (index + 1 < lines.length) {
        const cells = tableCells(lines[index + 1])
        if (!cells || cells.length !== header.length) break
        rows.push(cells)
        index += 1
      }
      blocks.push({ kind: 'table', header, rows, align: divider.map((cell) =>
        cell.endsWith(':') ? (cell.startsWith(':') ? 'center' : 'right') : 'left') })
      paragraph = null
      open = []
      continue
    }
    const item = listLine(line)
    if (item) {
      paragraph = null
      while (open.length > 0 && item.indent < open.at(-1).indent) open.pop()
      let list = open.at(-1)
      if (!list || item.indent > list.indent || list.ordered !== item.ordered) {
        const fresh = { kind: 'list', ordered: item.ordered, start: item.start, indent: item.indent, items: [] }
        if (list && item.indent > list.indent) {
          list.items.at(-1).lists.push(fresh)
        } else {
          // 같은 깊이에서 글머리 ↔ 번호가 바뀌면 옆에 새 목록을 둔다.
          if (list) open.pop()
          const parent = open.at(-1)
          if (parent) parent.items.at(-1).lists.push(fresh)
          else blocks.push(fresh)
        }
        open.push(fresh)
        list = fresh
      }
      list.items.push({ lines: [item.text], lists: [] })
      continue
    }
    const indent = indentOf(/^\s*/.exec(line)[0])
    while (open.length > 0 && indent <= open.at(-1).indent) open.pop()
    if (open.length > 0) {
      open.at(-1).items.at(-1).lines.push(line.trim())
      continue
    }
    if (!paragraph) {
      paragraph = { kind: 'paragraph', lines: [] }
      blocks.push(paragraph)
    }
    paragraph.lines.push(line.trim())
  }
  return blocks
}

// 한 줄을 **굵게** 와 나머지 글자로 나눈다. 짝이 없거나 안쪽이 빈칸으로 시작 · 끝나는 ** 는 글자 그대로 둔다.
export function boldParts(text) {
  const parts = []
  let last = 0
  for (const match of text.matchAll(boldText)) {
    if (match.index > last) parts.push({ text: text.slice(last, match.index), bold: false })
    parts.push({ text: match[1], bold: true })
    last = match.index + match[0].length
  }
  if (last < text.length) parts.push({ text: text.slice(last), bold: false })
  return parts
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
