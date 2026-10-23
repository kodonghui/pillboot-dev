import { create } from 'zustand'
import { useAuthStore } from './auth.js'

// 처방전 · 약봉투 사진으로 찾은 결과와 읽은 이름마다 고른 약(IMG-04 · 06). 이름 고쳐 검색(MED-03)에 다녀와도 남는다.
// 메모리에만 두어 새로고침하면 고른 약이 풀린다. 사진 파일 · 토큰 · 회원 정보는 담지 않는다.
// draft: { profileId, photoId(사진 번호 문자열, 모두 0건이면 null), result(PhotoIdentificationResponse), picks, done }
// picks[readNo]: 'none'(이 중에 없음) · { source: 'PRESCRIPTION_PHOTO', itemSeq } · { source: 'SEARCH', itemSeq, name, maker, material }
export const usePrescriptionDraft = create((set) => ({
  draft: null,
  start: (draft) => set({ draft: { ...draft, picks: {} } }),
  // 후보를 다시 받으면(저장 실패 뒤) 결과만 바꾸고 고른 약은 둔다.
  replaceResult: (result) => set(({ draft }) => (draft ? { draft: { ...draft, result } } : {})),
  // 한 이름에는 약 하나만 고른다. 다른 이름에서 고른 약은 그대로 둔다.
  pick: (readNo, value) => set(({ draft }) => (draft ? { draft: { ...draft, picks: { ...draft.picks, [readNo]: value } } } : {})),
  // 저장했거나 사진 등록을 그만두면(done) 다른 화면으로 넘어갈 때까지 결과를 그대로 두고, 처방전 화면이 내려갈 때 지운다.
  // 바로 지우면 아직 남은 처방전 화면이 결과를 잃고 지운 사진의 후보를 다시 불러오려 한다.
  finish: () => set(({ draft }) => (draft ? { draft: { ...draft, done: true } } : {})),
  clear: () => set({ draft: null }),
}))

// 로그아웃 · 로그인 만료 · 다른 회원 로그인 · 관리할 사람이 바뀌면 지운다(docs/auth-logout.md 의 개인 데이터 캐시).
// 이 파일이 로그인 상태를 구독하므로 auth.js 는 이 파일을 모른다(서로 가져오지 않음).
useAuthStore.subscribe((state, previous) => {
  if (state.token !== previous.token || state.member?.memberId !== previous.member?.memberId || state.profileId !== previous.profileId) {
    usePrescriptionDraft.getState().clear()
  }
})
