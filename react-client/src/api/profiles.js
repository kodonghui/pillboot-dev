import client from './client.js'

// ProfileController.getAllProfiles — GET /api/profiles
// token: 로그인 직후처럼 아직 저장하지 않은 토큰으로 부를 때 넘긴다.
export async function getProfiles(token) {
  const { data } = await client.get('/api/profiles', token ? { headers: { Authorization: `Bearer ${token}` } } : {})
  return data
}
