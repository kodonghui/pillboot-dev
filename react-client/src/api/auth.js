import client from './client.js'
import { getProfiles } from './profiles.js'

// AuthController.getConsentPolicies — GET /api/auth/consent-policies
export async function getConsentPolicies() {
  const { data } = await client.get('/api/auth/consent-policies')
  return data
}

// AuthController.checkEmailAvailability — GET /api/auth/email-availability
export async function checkEmailAvailability(email) {
  const { data } = await client.get('/api/auth/email-availability', { params: { email } })
  return data
}

// AuthController.signup — POST /api/auth/signup
export async function signup({ email, password, nickname, agreedTermsIds }) {
  await client.post('/api/auth/signup', { email, password, nickname, agreedTermsIds })
}

// AuthController.login — POST /api/auth/login
export async function login({ email, password }) {
  const { data } = await client.post('/api/auth/login', { email, password })
  return data
}

// AuthController.getMe — GET /api/auth/me
export async function getMe() {
  const { data } = await client.get('/api/auth/me')
  return data
}

// AuthController.login 다음 ProfileController.getAllProfiles. 로그인 직후 프로필 목록을 함께 불러온다(docs/auth-logout.md).
export async function loginWithProfiles({ email, password }) {
  const { accessToken, member } = await login({ email, password })
  const profiles = await getProfiles(accessToken)
  return { token: accessToken, member, profiles }
}
