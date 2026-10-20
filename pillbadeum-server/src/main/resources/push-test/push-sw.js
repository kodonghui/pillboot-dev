// 시험 페이지용 서비스 워커. 탭이 닫혀 있어도 크롬이 이 파일을 깨워 알림을 띄운다.
// 화면 담당의 실제 sw.js도 같은 모양({ title, body, url, tag })을 받으면 된다.
self.addEventListener("push", (event) => {
  const data = event.data ? event.data.json() : { title: "필받음", body: "알림이 왔어요." };
  event.waitUntil(self.registration.showNotification(data.title, {
    body: data.body,
    tag: data.tag,
    data: { url: data.url || "/dev/push-test" }
  }));
});

self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  event.waitUntil(clients.openWindow(event.notification.data.url));
});
