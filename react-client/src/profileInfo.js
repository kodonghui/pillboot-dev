// 프로필 값(서버 enum)을 화면 글자로 바꾸는 표와 복용 달력 색. 목업 PRF-01~04 기준.

export const relationLabels = {
  SELF: '본인',
  MOTHER: '엄마',
  FATHER: '아빠',
  DAUGHTER: '딸',
  SON: '아들',
  GRANDMA: '할머니',
  GRANDPA: '할아버지',
  SPOUSE: '배우자',
}

// "가족"을 고르면 펼치는 상세 관계(목업 순서)
export const familyRelations = ['MOTHER', 'FATHER', 'DAUGHTER', 'SON', 'GRANDMA', 'GRANDPA', 'SPOUSE']

export const genderLabels = { FEMALE: '여성', MALE: '남성', UNKNOWN: '모름' }

export function pregnancyLabel(pregnant) {
  return pregnant ? '임신 중' : '해당 없음'
}

// 복용 달력 색 1~8번의 이름. 색 값은 profileColors.css 한곳에 있다.
const colorNames = ['파랑', '주황', '보라', '초록', '자주', '하늘', '황토', '연보라']

// 복용 달력 색(자동). 서버에 색 값이 없어, 이 계정의 프로필을 번호(만든 순서)대로 세워 1~8번 색을 차례로 붙인다.
// 프로필 번호 % 8 이 아니라 이 계정 안의 순서라서 8명까지는 모두 다른 색이고, 9번째부터는 1번 색부터 다시 쓴다.
// 화면에서 계산만 하므로, 앞 번호의 프로필을 지우면 그 뒤 사람들의 색이 한 칸씩 당겨진다.
export function profileColor(profiles, profileId) {
  const ordered = profiles.map((profile) => profile.profileId).sort((a, b) => a - b)
  const index = ordered.indexOf(profileId)
  return index < 0 ? null : (index % colorNames.length) + 1
}

export function profileColorName(color) {
  return colorNames[color - 1]
}
