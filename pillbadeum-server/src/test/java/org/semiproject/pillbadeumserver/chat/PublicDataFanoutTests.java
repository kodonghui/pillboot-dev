package org.semiproject.pillbadeumserver.chat;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.chat.service.*;
import org.semiproject.pillbadeumserver.common.external.PublicDataClient;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import tools.jackson.databind.json.JsonMapper;

/** COMMON-001/006: 정상 지연 공급자가 내부 병렬 제출 때문에 실패하지 않는지 검증함. */
class PublicDataFanoutTests {
  @Test
  void officialAndDurShareFourPermitsWithoutRejectingHealthySlowResponses() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var workers = Executors.newVirtualThreadPerTaskExecutor();
    var executor = new SimpleAsyncTaskExecutor(); executor.setVirtualThreads(true);
    var active = new AtomicInteger(); var peak = new AtomicInteger(); var calls = new AtomicInteger();
    server.setExecutor(workers);
    server.createContext("/", exchange -> {
      int concurrent = active.incrementAndGet(); peak.accumulateAndGet(concurrent, Math::max); calls.incrementAndGet();
      try {
        try { Thread.sleep(1200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        byte[] bytes = "{\"header\":{\"resultCode\":\"00\"},\"body\":{\"totalCount\":0,\"items\":[]}}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
      } finally { active.decrementAndGet(); exchange.close(); }
    }); server.start();
    try {
      var client = new PublicDataClient(new JsonMapper(), "http://127.0.0.1:" + server.getAddress().getPort(), "synthetic", 2000, 5000, 4, 1000);
      var medications = new ArrayList<AnswerInput.Medication>();
      for (int i = 1; i <= 4; i++) medications.add(new AnswerInput.Medication("90000" + i, "합성약" + i, "합성제약", "OTC", 1, null, null, null, List.of(), LocalDate.now()));
      var input = new AnswerInput(1990, "MALE", false, "등록 약 안내", medications, List.of(), false);
      var deadline = ChatDeadline.after(Duration.ofSeconds(20));
      var official = executor.submit(() -> new OfficialDrugEvidence(client, executor, 4).collect(medications, deadline));
      var dur = executor.submit(() -> new DurLookupService(client, executor, 4).collect(input, deadline));
      var evidence = official.get(20, TimeUnit.SECONDS);
      var lookups = dur.get(20, TimeUnit.SECONDS);
      assertThat(evidence.lookups()).hasSize(8).allMatch(value -> !value.failed());
      assertThat(lookups).hasSize(8).allMatch(DurLookup::success);
      assertThat(calls.get()).isEqualTo(16);
      assertThat(peak.get()).isLessThanOrEqualTo(4);
    } finally { executor.close(); server.stop(0); workers.close(); }
  }

  @Test
  void exhaustedPermitStillHonorsRemainingRequestBudgetAndIsReusable() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var workers = Executors.newVirtualThreadPerTaskExecutor();
    var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
    server.setExecutor(workers);
    server.createContext("/", exchange -> {
      entered.countDown();
      try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
      byte[] bytes = "{\"header\":{\"resultCode\":\"00\"},\"body\":{\"totalCount\":0,\"items\":[]}}".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
    }); server.start();
    try {
      var client = new PublicDataClient(new JsonMapper(), "http://127.0.0.1:" + server.getAddress().getPort(), "synthetic", 2000, 5000, 1, 1000);
      var first = workers.submit(() -> client.get("/", Map.of(), Duration.ofSeconds(5)));
      assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
      long start = System.nanoTime();
      assertThatThrownBy(() -> client.get("/", Map.of(), Duration.ofMillis(60)))
          .isInstanceOf(org.semiproject.pillbadeumserver.common.exception.ExternalApiException.class);
      assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
      release.countDown(); first.get(3, TimeUnit.SECONDS);
      assertThat(client.get("/", Map.of(), Duration.ofSeconds(1)).path("totalCount").asInt()).isZero();
    } finally { release.countDown(); server.stop(0); workers.close(); }
  }
}
