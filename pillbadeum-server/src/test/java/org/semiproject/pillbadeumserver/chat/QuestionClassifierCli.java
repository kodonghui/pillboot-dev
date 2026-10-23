package org.semiproject.pillbadeumserver.chat;

import org.semiproject.pillbadeumserver.chat.service.QuestionClassifier;
import org.semiproject.pillbadeumserver.chat.domain.QuestionCategory;
import org.semiproject.pillbadeumserver.common.ai.OpenAiClient;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * CHAT-005 수동 실행 도구. test 작업에서 실행하지 않으며 명시적 Gradle 작업으로 실제 API를 한 번 호출한다.
 * 현재 서버 폴더의 .env와 환경변수를 읽지만 키·질문 원문을 로그나 DB에 기록하지 않는다.
 */
public final class QuestionClassifierCli {
    private QuestionClassifierCli() {
    }

    public static void main(String[] args) throws Exception {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Seoul"));
        Properties local = new Properties();
        if (Files.isRegularFile(Path.of(".env"))) {
            try (var reader = Files.newBufferedReader(Path.of(".env"), StandardCharsets.UTF_8)) {
                local.load(reader);
            }
        }
        configureRuntimeProxy();
        String key = setting(local, "OPENAI_API_KEY", "");
        String model = setting(local, "OPENAI_DECISION_MODEL", "");
        BigDecimal rate = new BigDecimal(setting(local, "OPENAI_DECISION_INPUT_USD_PER_MILLION", "0.10"));
        var openAi = new OpenAiClient(new ObjectMapper(), key, "https://api.openai.com/v1",
                "", model, "", "", "", 2000, 10000, 60000, 60000, 4, 1000, rate, 85_000);
        System.out.print("분류할 질문 한 줄을 입력하세요: ");
        System.out.flush();
        String question = new BufferedReader(new java.io.InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
        long start = System.nanoTime();
        try {
            QuestionCategory category = new QuestionClassifier(openAi).classify(question);
            double elapsed = (System.nanoTime() - start) / 1_000_000_000.0;
            System.out.printf(Locale.ROOT, "%n결과=%s | 전체 분류 시간=%.3f초%n", category, elapsed);
            if (!category.isPassed()) {
                System.out.println("안내=" + category.noticeMessage());
            }
            System.out.println("토큰·호출 시간·예상 USD 비용은 위 api=decisions 로그에서 확인합니다.");
        } catch (BusinessException error) {
            System.err.println("\n분류 실패: " + error.getErrorCode().getCode());
            System.exit(1);
        }
    }

    private static String setting(Properties local, String name, String fallback) {
        String environment = System.getenv(name);
        return environment != null ? environment : local.getProperty(name, fallback);
    }

    /** 클라우드에서 제공한 HTTPS 프록시와 시스템 Java 신뢰 저장소를 이 수동 실행 프로세스에만 적용한다. */
    private static void configureRuntimeProxy() {
        String proxy = System.getenv("HTTPS_PROXY");
        if (proxy == null || proxy.isBlank()) {
            proxy = System.getenv("https_proxy");
        }
        if (proxy != null && !proxy.isBlank() && System.getProperty("https.proxyHost") == null) {
            URI address = URI.create(proxy);
            System.setProperty("https.proxyHost", address.getHost());
            System.setProperty("https.proxyPort", Integer.toString(address.getPort() < 0 ? 80 : address.getPort()));
        }
        Path trustStore = Path.of("/etc/ssl/certs/java/cacerts");
        if (Files.isRegularFile(trustStore) && System.getProperty("javax.net.ssl.trustStore") == null) {
            System.setProperty("javax.net.ssl.trustStore", trustStore.toString());
        }
    }
}
