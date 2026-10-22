import { getVapidPublicKey, savePushSubscription } from './api/notifications.js'

// 이 크롬의 웹 푸시(목업 NTF-01). 알림을 띄우는 서비스 워커는 public/sw.js 다.
const workerUrl = '/sw.js'

// 웹 푸시를 쓸 수 있는 브라우저인지. 서비스 워커는 https 나 localhost 주소에서만 돌아간다.
export function pushSupported() {
  return window.isSecureContext && 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window
}

// 이 크롬에 구독이 남아 있는지. 서비스 워커를 등록한 적이 없으면 false.
export async function hasPushSubscription() {
  const registration = await navigator.serviceWorker.getRegistration()
  return Boolean(await registration?.pushManager.getSubscription())
}

// VAPID 공개 키(base64url)를 브라우저가 받는 바이트 배열로 바꾼다.
function keyBytes(base64url) {
  const base64 = base64url.replace(/-/g, '+').replace(/_/g, '/').padEnd(Math.ceil(base64url.length / 4) * 4, '=')
  return Uint8Array.from(atob(base64), (char) => char.charCodeAt(0))
}

// 서비스 워커를 등록하고 이 크롬을 구독해 서버에 저장한다. 알림 권한은 먼저 받아 두어야 한다.
// 서버 키가 바뀌었을 수 있어 예전 구독은 지우고 새로 만든다(서버 시험 화면 push-test.js 와 같은 순서).
export async function subscribePush() {
  const registration = await navigator.serviceWorker.register(workerUrl)
  await navigator.serviceWorker.ready
  const publicKey = await getVapidPublicKey()
  const previous = await registration.pushManager.getSubscription()
  if (previous) await previous.unsubscribe()
  const subscription = await registration.pushManager.subscribe({
    userVisibleOnly: true,
    applicationServerKey: keyBytes(publicKey),
  })
  const { endpoint, keys } = subscription.toJSON()
  await savePushSubscription({ endpoint, keys, userAgent: navigator.userAgent })
}

// 이 크롬의 구독을 지운다. 서버에 저장된 구독은 알림 끄기(PUT)가 모두 지운다.
export async function unsubscribePush() {
  const registration = await navigator.serviceWorker.getRegistration()
  const subscription = await registration?.pushManager.getSubscription()
  await subscription?.unsubscribe()
}
