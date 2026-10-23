import client from './client.js'

// MedicationDrugChoiceController.get — GET /api/profiles/{profileId}/drugs/schedule-choices
export async function getScheduleChoices(profileId) {
  const { data } = await client.get(`/api/profiles/${profileId}/drugs/schedule-choices`)
  return data
}

// MedicationScheduleController.get — GET /api/profiles/{profileId}/drugs/{myDrugId}/schedule
export async function getSchedule(profileId, myDrugId) {
  const { data } = await client.get(`/api/profiles/${profileId}/drugs/${myDrugId}/schedule`)
  return data
}

// MedicationScheduleController.save — PUT /api/profiles/{profileId}/drugs/{myDrugId}/schedule
export async function saveSchedule(profileId, myDrugId, request) {
  const { data } = await client.put(`/api/profiles/${profileId}/drugs/${myDrugId}/schedule`, request)
  return data
}

// MedicationScheduleController.stop — POST /api/profiles/{profileId}/drugs/{myDrugId}/schedule/stop
export async function stopSchedule(profileId, myDrugId) {
  const { data } = await client.post(`/api/profiles/${profileId}/drugs/${myDrugId}/schedule/stop`)
  return data
}

// MedicationScheduleController.delete — DELETE /api/profiles/{profileId}/drugs/{myDrugId}/schedule
export async function deleteSchedule(profileId, myDrugId) {
  await client.delete(`/api/profiles/${profileId}/drugs/${myDrugId}/schedule`)
}

// MedicationCalendarController.get — GET /api/calendar
export async function getCalendar(from, to) {
  const { data } = await client.get('/api/calendar', { params: { from, to } })
  return data
}

// MedicationDoseQueryController.get — GET /api/calendar/doses
export async function getDoses(date, time) {
  const { data } = await client.get('/api/calendar/doses', { params: { date, time } })
  return data
}
