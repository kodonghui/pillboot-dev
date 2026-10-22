// 복약 알림 서비스 워커(목업 NTF-03). 탭이 닫혀 있어도 크롬이 이 파일을 깨워 알림을 띄운다.
// 알림 화면(NTF-01)에서 "알림 켜기"를 누를 때만 등록한다(src/push.js).
// 서버(MedicationReminderService)가 보내는 { title, body, tag, url } 을 그대로 보여 주고, 누르면 url(/doses?date&time)을 연다.
self.addEventListener('push', (event) => {
  const message = event.data ? event.data.json() : {}
  event.waitUntil(
    self.registration.showNotification(message.title ?? '필받음', {
      body: message.body,
      tag: message.tag,
      data: { url: message.url ?? '/' },
    }),
  )
})

self.addEventListener('notificationclick', (event) => {
  event.notification.close()
  event.waitUntil(self.clients.openWindow(event.notification.data?.url ?? '/'))
})
