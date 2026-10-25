package org.semiproject.pillbadeumserver.drug;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.semiproject.pillbadeumserver.common.external.PublicDataClient;
import org.semiproject.pillbadeumserver.drug.domain.MyDrugRepository;
import org.semiproject.pillbadeumserver.drug.service.*;
import org.semiproject.pillbadeumserver.image.service.PillCatalog;
import org.springframework.data.domain.PageRequest;
import tools.jackson.databind.json.JsonMapper;

/** 목록 전용 응답이0건이어도 정상인 공식 상세 연산으로 이름·페이지·처방전 함량을 조회하는 HTTP 회귀임. */
class PermitNameLookupHttpTests {
  final JsonMapper json = new JsonMapper();
  final List<String> requests = new ArrayList<>();
  final MyDrugRepository mine = mock(MyDrugRepository.class);
  HttpServer server;
  PublicDataClient client;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      String path = exchange.getRequestURI().getPath();
      String query = URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
      requests.add(path + "?" + query);
      boolean detail = path.endsWith("/getDrugPrdtPrmsnDtlInq08");
      boolean secondPage = query.contains("pageNo=2");
      var items = !detail || query.contains("item_name=없는약") ? List.of()
          : secondPage ? List.of(row("900004", "합성정4mg"), row("900005", "합성정5mg"), row("900006", "합성정6mg"))
          : List.of(row("900001", "합성정500밀리그람(합성성분)"), row("900002", "합성정160밀리그램"));
      byte[] bytes = json.writeValueAsBytes(Map.of("header", Map.of("resultCode", "00"),
          "body", Map.of("totalCount", items.isEmpty() ? 0 : secondPage ? 7 : 2, "items", items)));
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });
    server.start();
    client = new PublicDataClient(json, "http://127.0.0.1:" + server.getAddress().getPort(),
        "synthetic-key", 1000, 2000, 4, 1000);
  }

  @AfterEach void stop() { server.stop(0); }

  @Test
  void nameSearchReadsAvailableOfficialOperationAndKeepsGenuineZeroResults() {
    var search = search();
    var page = search.search("합성정", PageRequest.of(0, 20));
    assertThat(page.getContent()).extracting(x -> x.itemSeq()).containsExactly("900001", "900002");
    assertThat(page.getTotalElements()).isEqualTo(2);
    assertThat(search.search("없는약", PageRequest.of(0, 20)).isEmpty()).isTrue();
    assertThat(requests).allMatch(x -> x.startsWith("/1471000/DrugPrdtPrmsnInfoService08/getDrugPrdtPrmsnDtlInq08?"));
    verifyNoInteractions(mine);
  }

  @Test
  void prescriptionLookupUsesOfficialOperationAndRetainsStrengthFiltering() {
    var rows = new PrescriptionDrugLookup(client).find("합성정500밀리그램", "합성제약");
    assertThat(rows).singleElement().satisfies(x -> assertThat(x.path("ITEM_SEQ").asString()).isEqualTo("900001"));
    assertThat(requests).singleElement().satisfies(x -> {
      assertThat(x).contains("getDrugPrdtPrmsnDtlInq08?", "item_name=합성정500밀리그램", "entp_name=합성제약", "numOfRows=100");
    });
  }

  @Test
  void pageNumberSizeAndProviderTotalArePreserved() {
    var page = search().search("합성정", PageRequest.of(1, 3));
    assertThat(page.getContent()).extracting(x -> x.itemSeq()).containsExactly("900004", "900005", "900006");
    assertThat(page.getTotalElements()).isEqualTo(7);
    assertThat(page.getNumber()).isEqualTo(1);
    assertThat(requests).singleElement().satisfies(x -> assertThat(x).contains("pageNo=2", "numOfRows=3"));
  }

  DrugSearchService search() {
    return new DrugSearchService(client, mine, mock(ProfileAccessChecker.class), mock(PillCatalog.class));
  }
  static Map<String, Object> row(String code, String name) {
    return Map.of("ITEM_SEQ", code, "ITEM_NAME", name, "ENTP_NAME", "합성제약", "MAIN_ITEM_INGR", "[SYN]합성성분");
  }
}
