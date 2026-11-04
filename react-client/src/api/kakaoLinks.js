import client from './client.js'

// 카카오톡 약봉이와 이 계정의 연결(DEC-029, PRF-01 카드 · PRF-06). 연결 코드는 응답에서 한 번만 받으므로 저장하거나 기록하지 않는다.

// KakaoLinkController.list — GET /api/kakao-links
export async function getKakaoLinks() {
  const { data } = await client.get('/api/kakao-links')
  return data
}

// KakaoLinkController.createCode — POST /api/kakao-link-codes
// 연결 안내를 확인했을 때만 부른다(agreed 는 true 만 받는다).
export async function createKakaoLinkCode() {
  const { data } = await client.post('/api/kakao-link-codes', { agreed: true })
  return data
}

// KakaoLinkController.delete — DELETE /api/kakao-links/{linkId}
export async function deleteKakaoLink(linkId) {
  await client.delete(`/api/kakao-links/${linkId}`)
}
