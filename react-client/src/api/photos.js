import client from './client.js'

// PhotoIdentificationController.identify — POST /api/profiles/{profileId}/photo-identifications
export async function identifyPhoto(profileId, kind, files) {
  const form = new FormData()
  Object.entries(files).forEach(([name, file]) => form.append(name, file))
  const { data } = await client.post(`/api/profiles/${profileId}/photo-identifications`, form, { params: { kind } })
  return data
}

// PhotoIdentificationController.candidates — GET /api/photos/{photoId}/candidates
export async function getPhotoCandidates(photoId) {
  const { data } = await client.get(`/api/photos/${photoId}/candidates`)
  return data
}

// PhotoIdentificationController.image — GET /api/photos/{photoId}/image
export async function getPhotoImage(photoId) {
  const { data } = await client.get(`/api/photos/${photoId}/image`, { responseType: 'blob' })
  return data
}

// PhotoIdentificationController.delete — DELETE /api/photos/{photoId}
export async function deletePhoto(photoId) {
  await client.delete(`/api/photos/${photoId}`)
}

// PhotoIdentificationController.result — GET /api/profiles/{profileId}/pill-identifications/{identificationId}
export async function getPillIdentification(profileId, identificationId) {
  const { data } = await client.get(`/api/profiles/${profileId}/pill-identifications/${identificationId}`)
  return data
}

// PhotoIdentificationController.cancel — DELETE /api/profiles/{profileId}/pill-identifications/{identificationId}
export async function cancelPillIdentification(profileId, identificationId) {
  await client.delete(`/api/profiles/${profileId}/pill-identifications/${identificationId}`)
}

// PillIdentificationController.identify — POST /api/images/identify
export async function identifyPillClues(profileId, clues, previousIdentificationId) {
  const { data } = await client.post('/api/images/identify', clues, {
    params: { view: 'details', profileId, previousIdentificationId },
  })
  return data
}
