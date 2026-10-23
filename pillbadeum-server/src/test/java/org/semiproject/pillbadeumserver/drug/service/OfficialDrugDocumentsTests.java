package org.semiproject.pillbadeumserver.drug.service;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.common.exception.ExternalApiException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import static org.assertj.core.api.Assertions.*;

class OfficialDrugDocumentsTests {
    private HttpServer server;
    private ExecutorService threads;
    private URI base;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        threads = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(threads); server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }
    @AfterEach void stop() { server.stop(0); threads.shutdownNow(); }

    @Test void readsCanonicalOfficialPdfAsTextWithoutSendingGatewayCredentials() throws Exception {
        byte[] pdf;
        try (var doc = new PDDocument(); var out = new ByteArrayOutputStream()) {
            var page = new PDPage(); doc.addPage(page);
            try (var stream = new PDPageContentStream(doc, page)) {
                stream.beginText(); stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(20, 700); stream.showText("Synthetic official document"); stream.endText();
            }
            doc.save(out); pdf = out.toByteArray();
        }
        server.createContext("/pbp/cmn/pdfDownload/199900001/EE", exchange -> {
            assertThat(exchange.getRequestURI().getQuery()).isNull();
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
            exchange.sendResponseHeaders(200, pdf.length); exchange.getResponseBody().write(pdf); exchange.close();
        });
        assertThat(new OfficialDrugDocuments(base, 1000, 1000, 1).read("199900001", "EE"))
                .isEqualTo("Synthetic official document");
    }

    @Test void invalidInputAndRedirectDoNotReachAnArbitraryAddress() {
        var client = new OfficialDrugDocuments(base, 1000, 1000, 1);
        assertThatThrownBy(() -> client.read("../other", "EE")).isInstanceOf(IllegalArgumentException.class);
        var redirected = new AtomicInteger();
        server.createContext("/pbp/cmn/pdfDownload/199900001/EE", exchange -> {
            exchange.getResponseHeaders().add("Location", base + "/other");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        server.createContext("/other", exchange -> { redirected.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        assertThatThrownBy(() -> client.read("199900001", "EE")).isInstanceOf(ExternalApiException.class);
        assertThat(redirected).hasValue(0);
    }

    @Test void timeoutAndNonPdfRemainExternalFailuresInsteadOfMedicalText() {
        server.createContext("/pbp/cmn/pdfDownload/199900001/EE", exchange -> {
            byte[] body = "not a PDF".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        assertThatThrownBy(() -> new OfficialDrugDocuments(base, 1000, 1000, 1).read("199900001", "EE"))
                .isInstanceOf(ExternalApiException.class);
        server.createContext("/pbp/cmn/pdfDownload/199900001/UD", exchange -> {
            try { Thread.sleep(200); exchange.sendResponseHeaders(200, -1); } catch (Exception ignored) {} finally { exchange.close(); }
        });
        assertThatThrownBy(() -> new OfficialDrugDocuments(base, 1000, 40, 1).read("199900001", "UD"))
                .isInstanceOfSatisfying(ExternalApiException.class, error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.EXTERNAL_API_TIMEOUT));
    }
}
