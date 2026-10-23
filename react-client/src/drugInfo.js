// 내 약 · 약 정보 응답을 화면 글자로 바꾸는 도우미. 목업 MED-01~04 기준.

// 등록 방법: 목업에 표기가 있는 것은 목업대로, 나머지는 서버 sourceName("이름 검색" 등)을 쓴다.
const sourceLabels = { PRESCRIPTION_PHOTO: '처방전·약봉투 사진' }

export function drugSourceLabel(drug) {
  return sourceLabels[drug.source] ?? drug.sourceName ?? drug.source
}

// 복약 일정이 있는지. 서버 MyDrug.hasSchedule 과 같은 조건(시작일 · 종료일 · 복용 시각)
export function hasSchedule(drug) {
  return Boolean(drug.startDate && drug.endDate && drug.doseTimes?.length)
}

// 공식 정보 중 화면에 보이는 항목(목업: 효능 · 사용법 · 주의사항). 허가정보로 대신 받으면 제목이 "효능효과" 등으로 온다.
const shownSections = ['EFFICACY', 'USAGE', 'PRECAUTION']

export function guideSections(guide) {
  return guide.sections.filter((section) => shownSections.includes(section.key))
}

// 공식 정보 제공 기관(목업 고정 문구)
export const guideProvider = '식품의약품안전처 공공데이터'
