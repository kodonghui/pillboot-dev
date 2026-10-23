import client from './client.js'

// MyDrugController.getMyDrugs — GET /api/profiles/{profileId}/drugs
export async function getMyDrugs(profileId) {
  const { data } = await client.get(`/api/profiles/${profileId}/drugs`)
  return data
}

// MyDrugController.getMyDrug — GET /api/profiles/{profileId}/drugs/{myDrugId}
export async function getMyDrug(profileId, myDrugId) {
  const { data } = await client.get(`/api/profiles/${profileId}/drugs/${myDrugId}`)
  return data
}

// MyDrugController.register — POST /api/profiles/{profileId}/drugs
export async function registerDrugs(profileId, request) {
  const { data } = await client.post(`/api/profiles/${profileId}/drugs`, request)
  return data
}

// MyDrugController.deleteMyDrug — DELETE /api/profiles/{profileId}/drugs/{myDrugId}
export async function deleteMyDrug(profileId, myDrugId) {
  await client.delete(`/api/profiles/${profileId}/drugs/${myDrugId}`)
}

// DrugController.search — GET /api/drugs/search
export async function searchDrugs({ name, profileId, page, size }) {
  // page 는 1부터 센다(서버 one-indexed-parameters). OpenAPI 의 pageable 은 page · size 쿼리로 받는다.
  const { data } = await client.get('/api/drugs/search', { params: { name, profileId, page, size } })
  return data
}

// DrugController.getDrug — GET /api/drugs/{itemSeq}
export async function getDrug(itemSeq) {
  const { data } = await client.get(`/api/drugs/${itemSeq}`)
  return data
}

// DrugController.getGuide — GET /api/drugs/{itemSeq}/guide
export async function getDrugGuide(itemSeq) {
  const { data } = await client.get(`/api/drugs/${itemSeq}/guide`)
  return data
}
