import { create } from 'zustand'
import { persist } from 'zustand/middleware'

// 관리할 사람: 저장된 값이 이 회원의 프로필이면 그대로, 아니면 본인(SELF), 본인이 없으면 목록 맨 위(docs/auth-logout.md).
function chooseProfileId(profiles, storedProfileId) {
  if (profiles.some((profile) => profile.profileId === storedProfileId)) return storedProfileId
  return (profiles.find((profile) => profile.relationshipType === 'SELF') ?? profiles[0])?.profileId ?? null
}

// 로그인 정보(토큰 · 회원 정보)와 관리할 사람. localStorage 에 보관한다(docs/auth-logout.md).
export const useAuthStore = create(
  persist(
    (set) => ({
      token: null,
      member: null,
      profileId: null,
      // 로그인 정보가 사라진 까닭(logout · expired). 보호된 화면에서 어디로 보낼지 정할 때만 쓰고 저장하지 않는다.
      exitReason: null,
      signIn: ({ token, member, profiles }) =>
        set((state) => ({ token, member, profileId: chooseProfileId(profiles, state.profileId), exitReason: null })),
      setMember: (member) => set({ member }),
      // 로그아웃: 토큰 · 회원 정보 · 관리할 사람을 지운다.
      signOut: () => set({ token: null, member: null, profileId: null, exitReason: 'logout' }),
      // 로그인 만료(401): 인증 상태를 지운다. 관리할 사람은 다시 로그인할 때 위 규칙으로 확인한다.
      expire: () => set({ token: null, member: null, exitReason: 'expired' }),
    }),
    {
      name: 'pillbadeum-auth',
      partialize: ({ token, member, profileId }) => ({ token, member, profileId }),
    },
  ),
)
