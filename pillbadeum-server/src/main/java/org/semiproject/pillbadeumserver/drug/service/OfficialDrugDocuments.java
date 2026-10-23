package org.semiproject.pillbadeumserver.drug.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.Semaphore;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.semiproject.pillbadeumserver.common.exception.ExternalApiException;
import org.semiproject.pillbadeumserver.common.external.ExternalApiCaller;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** API가 본문 대신 PDF 주소만 제공한 품목의 공식 원문을 읽음. 임의 URL·리다이렉트·인증키 전달은 없음. */
@Component
public class OfficialDrugDocuments {
    private static final String API = "의약품 공식 원문";
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private final URI base;
    private final HttpClient client;
    private final Duration timeout;
    private final Semaphore permits;

    @Autowired
    public OfficialDrugDocuments(@Value("${public-data.connect-timeout-ms}") long connectMs,
                                 @Value("${public-data.read-timeout-ms}") long readMs,
                                 @Value("${public-data.max-concurrent-calls:4}") int concurrency) {
        this(URI.create("https://nedrug.mfds.go.kr"), connectMs, readMs, concurrency);
    }

    OfficialDrugDocuments(URI base, long connectMs, long readMs, int concurrency) {
        this.base = base;
        this.timeout = Duration.ofMillis(readMs);
        this.permits = new Semaphore(concurrency, true);
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectMs))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public String read(String itemSeq, String kind) {
        if (itemSeq == null || kind == null || !itemSeq.matches("[0-9]{9}") || !Set.of("EE", "UD", "NB").contains(kind))
            throw new IllegalArgumentException("공식 품목코드와 문서 종류가 필요합니다.");
        // 공공 API의 대문자 /PBP/CMN/PDFDOWNLOAD는404임. 공식 사이트의 실제 경로를 사용함.
        URI uri = base.resolve("/pbp/cmn/pdfDownload/" + itemSeq + "/" + kind);
        var request = HttpRequest.newBuilder(uri).timeout(timeout).GET().build();
        return ExternalApiCaller.withPermit(API, permits, timeout.toMillis(), () -> read(request));
    }

    private String read(HttpRequest request) {
        try {
            var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) throw ExternalApiException.failed(API, "공식 원문을 불러오지 못했습니다.", null);
            byte[] bytes = response.body();
            if (bytes.length > MAX_BYTES) return DrugGuideService.OMITTED_SECTION;
            try (var pdf = Loader.loadPDF(bytes)) {
                // 일부 공식 PDF는 단어 사이 공백을 NUL로 추출함. 수치·부등호는 그대로 보존함.
                String text = new PDFTextStripper().getText(pdf).replace('\0', ' ').strip();
                if (text.isBlank()) throw ExternalApiException.failed(API, "공식 원문의 글자를 확인하지 못했습니다.", null);
                return text;
            }
        } catch (HttpTimeoutException error) {
            throw ExternalApiException.timeout(API, null);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw ExternalApiException.timeout(API, null);
        } catch (IOException error) {
            // 원문·주소·품목·인증정보를 예외 로그에 복사하지 않음.
            throw ExternalApiException.failed(API, "공식 원문을 읽지 못했습니다.", null);
        }
    }
}
