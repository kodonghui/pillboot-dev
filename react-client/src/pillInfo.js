// 알약 사진 식별의 단서 · 후보 비교 표기. 값은 서버 PillIdentification(단서) · PillMatchDetail(비교 근거) 기준이다.

// 단서 보완(IMG-07)에서 고르는 모양 · 색상. 공공데이터 낱알식별의 표기와 같은 이름을 보낸다.
export const pillShapes = ['원형', '타원형', '장방형', '반원형', '삼각형', '사각형', '마름모형', '오각형', '육각형', '팔각형', '기타']
export const pillColors = ['하양', '노랑', '주황', '분홍', '빨강', '갈색', '연두', '초록', '청록', '파랑', '남색', '자주', '보라', '회색', '검정', '투명']

// 각인 · 분할선 상태(ObservationState)
export const stateLabels = { READABLE: '읽힘', NONE: '없음', UNREADABLE: '미판독' }

// 분할선 값(MINUS · PLUS)의 기호와 읽는 이름
export const lineNames = { MINUS: { symbol: '−', name: '일자' }, PLUS: { symbol: '+', name: '십자' } }

// 서버가 같은 모양 · 색으로 보는 다른 이름(PillSimilarity). 비교 근거의 색은 영문 코드로 온다.
const shapeAliases = {
  둥근형: '원형', ROUND: '원형', CIRCLE: '원형',
  타원: '타원형', OVAL: '타원형', ELLIPSE: '타원형',
  직사각형: '장방형', OBLONG: '장방형', RECTANGLE: '장방형',
}
const colorAliases = {
  하얀색: '하양', 흰색: '하양', 백색: '하양', WHITE: '하양',
  노란색: '노랑', 황색: '노랑', YELLOW: '노랑',
  분홍색: '분홍', PINK: '분홍',
  파란색: '파랑', 청색: '파랑', BLUE: '파랑',
  주황색: '주황', ORANGE: '주황',
  빨간색: '빨강', 적색: '빨강', RED: '빨강',
  초록색: '초록', 녹색: '초록', GREEN: '초록',
  BROWN: '갈색',
  검정색: '검정', 검은색: '검정', 흑색: '검정', BLACK: '검정',
  보라색: '보라', PURPLE: '보라',
  GRAY: '회색', GREY: '회색',
}

const compact = (value) => value.replace(/\s/g, '').toUpperCase()

// 'OBLONG' · '직사각형' → '장방형'. 값이 없으면 null
export function shapeName(value) {
  if (!value) return null
  return shapeAliases[compact(value)] ?? value.trim()
}

// 한 값에 색이 여럿일 수 있다('하양, 노랑' · ['WHITE', 'YELLOW']). 서버처럼 , / | · 공백으로 나눈다.
export function colorNames(...values) {
  const parts = values.flat().filter(Boolean).flatMap((value) => value.split(/[,/|·\s]+/)).filter(Boolean)
  return [...new Set(parts.map((part) => colorAliases[compact(part)] ?? part))]
}

const matchFields = [
  { field: 'printFront', label: '사진 1 각인' },
  { field: 'printBack', label: '사진 2 각인' },
  { field: 'shape', label: '모양' },
  { field: 'color', label: '색상' },
]

function valueText(field, value) {
  if (field === 'color') return colorNames(value).join(', ') || null
  if (field === 'shape') return shapeName(value)
  return value || null
}

// 후보 비교 표의 줄(IMG-05). 분할선은 목업처럼 표에서 뺀다.
// mine: 사진에서 읽은 값(못 읽은 각인은 상태 이름) · official: 공식 값(없으면 null) · matched: 같은 값인지.
// 서버는 값이 다른 후보를 돌려주지 않으므로 결과는 일치(MATCH)와 확인 못 함(UNKNOWN · NOT_COMPARED)뿐이다.
export function matchRows(candidate, clues) {
  return matchFields.flatMap(({ field, label }) => {
    const detail = candidate.matchDetails?.find((item) => item.field === field)
    if (!detail) return []
    const mine = valueText(field, detail.observedValue) ?? stateLabels[clues?.[`${field}State`]] ?? stateLabels.UNREADABLE
    return [{ field, label, mine, official: valueText(field, detail.referenceValue), matched: detail.result === 'MATCH' }]
  })
}
