import client from './client.js'

// NotificationSettingController.get — GET /api/notification-settings
export async function getNotificationSettings() {
  const { data } = await client.get('/api/notification-settings')
  return data
}

// NotificationSettingController.change — PUT /api/notification-settings
export async function changeNotificationSettings(request) {
  const { data } = await client.put('/api/notification-settings', request)
  return data
}

// PushSubscriptionController.publicKey — GET /api/push/vapid-public-key
export async function getVapidPublicKey() {
  const { data } = await client.get('/api/push/vapid-public-key')
  return data.publicKey
}

// PushSubscriptionController.subscribe — POST /api/push/subscriptions
export async function savePushSubscription(subscription) {
  const { data } = await client.post('/api/push/subscriptions', subscription)
  return data
}
