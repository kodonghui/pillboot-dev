import client from './client.js'

// ProfileController.getAllProfiles — GET /api/profiles
// token: 로그인 직후처럼 아직 저장하지 않은 토큰으로 부를 때 넘긴다.
export async function getProfiles(token) {
  const { data } = await client.get('/api/profiles', token ? { headers: { Authorization: `Bearer ${token}` } } : {})
  return data
}

// ProfileController.createProfiles — POST /api/profiles
export async function createProfile(profile) {
  const { data } = await client.post('/api/profiles', profile)
  return data
}

// ProfileController.updateProfile — PUT /api/profiles/{profileId}
export async function updateProfile(profileId, profile) {
  const { data } = await client.put(`/api/profiles/${profileId}`, profile)
  return data
}

// ProfileController.deleteProfile — DELETE /api/profiles/{profileId}
export async function deleteProfile(profileId) {
  await client.delete(`/api/profiles/${profileId}`)
}
