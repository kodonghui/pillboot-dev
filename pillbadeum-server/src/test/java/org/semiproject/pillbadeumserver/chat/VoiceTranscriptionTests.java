package org.semiproject.pillbadeumserver.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.semiproject.pillbadeumserver.chat.service.AudioClip;
import org.semiproject.pillbadeumserver.chat.service.VoiceTranscriptionClient;
import org.semiproject.pillbadeumserver.chat.service.VoiceTranscriptionService;
import org.semiproject.pillbadeumserver.common.exception.AiUnavailableException;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.common.exception.ExternalApiException;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.ObjectMapper;

/**
 * V3-KAKAO-006(TASK-032): 음성 형식·재생 시간을 파일 머리에서 읽고, 1분 초과·형식 밖·빈 결과·실패를 받아쓰기 전후에 거르는지
 * 실제 과금 호출 없이 로컬 HTTP 대역으로 확인한다. 음성·받아쓴 글이 로그에 남지 않는지도 본다.
 */
@ExtendWith(OutputCaptureExtension.class)
class VoiceTranscriptionTests {
    private static final String SPOKEN = "타이레놀 등록해 줘";
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> received = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private HttpServer server;
    private String baseUrl;
    private volatile int status = 200;
    private volatile String reply = "{\"text\":\"  " + SPOKEN + " \",\"languages\":[{\"code\":\"ko\"}]}";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/audio/transcriptions", exchange -> {
            calls.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
            respond(exchange, status, reply);
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void m4aDurationIsReadFromMvhdEvenAfterALargeMdatAndWavFromItsChunks() {
        assertThat(AudioClip.read(m4a(mvhd0(1000, 15_000), false))).contains(new AudioClip(AudioClip.Format.M4A, 15_000));
        // moov가 mdat 뒤에 있고 mdat이 64비트 크기를 써도 상자 크기로 건너뛰어 찾음. 판 1은 64비트 재생 시간.
        assertThat(AudioClip.read(m4a(mvhd1(44_100, 44_100L * 61), true))).contains(new AudioClip(AudioClip.Format.M4A, 61_000));
        assertThat(AudioClip.read(wav(8_000, 8_000 * 2 + 4_000, 0))).contains(new AudioClip(AudioClip.Format.WAV, 2_500));
        // data 크기를 실제보다 크게 적은 WAV(녹음 중 끊김)는 있는 만큼만 셈.
        assertThat(AudioClip.read(wav(8_000, 8_000, 0x7FFF_FFFF))).contains(new AudioClip(AudioClip.Format.WAV, 1_000));
    }

    @Test
    void filesWithoutAReadableDurationAreNotAudioClips() {
        byte[] mp3 = {'I', 'D', '3', 4, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        assertThat(AudioClip.read(mp3)).isEmpty();
        assertThat(AudioClip.read("텍스트 파일입니다".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(AudioClip.read(m4a(mvhd0(0, 15_000), false))).as("timescale 0").isEmpty();
        assertThat(AudioClip.read(m4a(mvhd0(1000, 0xFFFFFFFFL), false))).as("재생 시간 모름").isEmpty();
        byte[] noMoov = concat(box("ftyp", "M4A \0\0\0\0M4A isom".getBytes(StandardCharsets.US_ASCII)), box("mdat", new byte[64]));
        assertThat(AudioClip.read(noMoov)).isEmpty();
        byte[] whole = m4a(mvhd0(1000, 15_000), true);
        byte[] truncated = java.util.Arrays.copyOf(whole, whole.length - 20);   // 맨 끝의 moov가 잘림
        assertThat(AudioClip.read(truncated)).as("mvhd가 잘림").isEmpty();
        byte[] wavWithoutFmt = concat("RIFF\0\0\0\0WAVE".getBytes(StandardCharsets.US_ASCII), chunk("data", new byte[16]));
        assertThat(AudioClip.read(wavWithoutFmt)).isEmpty();
    }

    @Test
    void clientSendsOnlyTheAudioModelAndFormatAndReturnsTheText() {
        String text = client("gpt-transcribe").transcribe(m4a(mvhd0(1000, 15_000), false), AudioClip.Format.M4A);

        assertThat(text).isEqualTo("  " + SPOKEN + " ");
        assertThat(authorization.get()).isEqualTo("Bearer private-ai-key");
        String body = received.get();
        assertThat(body).contains("name=\"file\"; filename=\"voice.m4a\"").contains("Content-Type: audio/mp4")
                .contains("name=\"model\"").contains("gpt-transcribe")
                .contains("name=\"response_format\"").contains("json");
        // 언어 칸은 모델마다 이름이 달라 보내지 않고, 힌트 글·사용자 정보도 보내지 않음(8.3).
        assertThat(body).doesNotContain("name=\"language").doesNotContain("name=\"prompt\"");
    }

    @Test
    void clientFailuresBecomeTheExistingUnavailableErrorsAndAMissingModelCallsNothing() {
        byte[] audio = m4a(mvhd0(1000, 15_000), false);
        assertThatThrownBy(() -> client("").transcribe(audio, AudioClip.Format.M4A)).isInstanceOf(AiUnavailableException.class);
        assertThat(calls).hasValue(0);

        status = 401;
        assertThatThrownBy(() -> client("gpt-transcribe").transcribe(audio, AudioClip.Format.M4A)).isInstanceOf(AiUnavailableException.class);
        status = 500;
        assertThatThrownBy(() -> client("gpt-transcribe").transcribe(audio, AudioClip.Format.M4A)).isInstanceOf(ExternalApiException.class);
        status = 200;
        reply = "{\"languages\":[]}";
        assertThatThrownBy(() -> client("gpt-transcribe").transcribe(audio, AudioClip.Format.M4A)).isInstanceOf(ExternalApiException.class);
        reply = "not json";
        assertThatThrownBy(() -> client("gpt-transcribe").transcribe(audio, AudioClip.Format.M4A)).isInstanceOf(ExternalApiException.class);
    }

    @Test
    void serviceReturnsTheStrippedTextAndLogsNoAudioOrWords(CapturedOutput output) {
        var service = new VoiceTranscriptionService(client("gpt-transcribe"));

        var response = service.transcribe(file(m4a(mvhd0(1000, 15_000), false)));

        assertThat(response.text()).isEqualTo(SPOKEN);
        assertThat(output).contains("voice_transcription format=M4A").contains("audioMs=15000").doesNotContain(SPOKEN);
    }

    @Test
    void tooLongTooLargeAndUnreadableAudioIsRefusedBeforeCallingAndAnEmptyResultIsUnprocessable() {
        var service = new VoiceTranscriptionService(client("gpt-transcribe"));

        assertCode(() -> service.transcribe(file(m4a(mvhd0(1000, 60_001), false))), ErrorCode.AUDIO_TOO_LONG);
        assertCode(() -> service.transcribe(file(new byte[5 * 1024 * 1024 + 1])), ErrorCode.FILE_TOO_LARGE);
        assertCode(() -> service.transcribe(file(new byte[]{'I', 'D', '3', 4, 0, 0, 0, 0, 0, 0, 0, 0})), ErrorCode.INVALID_FILE);
        assertCode(() -> service.transcribe(file(new byte[0])), ErrorCode.INVALID_INPUT);
        assertThat(calls).as("받아쓰기를 부르지 않음").hasValue(0);

        // 딱 1분은 받음.
        assertThat(service.transcribe(file(m4a(mvhd0(1000, 60_000), false))).text()).isEqualTo(SPOKEN);
        reply = "{\"text\":\"  \\n \"}";
        assertCode(() -> service.transcribe(file(wav(8_000, 8_000, 0))), ErrorCode.TRANSCRIPTION_EMPTY);
        assertThat(calls).hasValue(2);
    }

    private VoiceTranscriptionClient client(String model) {
        return new VoiceTranscriptionClient("private-ai-key", baseUrl, model, 1_000, 5_000, 2, 1_000, json);
    }

    private static MockMultipartFile file(byte[] bytes) {
        return new MockMultipartFile("audio", "kakao-voice", "application/octet-stream", bytes);
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(code));
    }

    /** ftyp·mdat·moov(mvhd). moovLast면 mdat을 64비트 크기 상자로 앞에 둠. */
    private static byte[] m4a(byte[] mvhd, boolean moovLast) {
        byte[] ftyp = box("ftyp", "M4A \0\0\0\0M4A isom".getBytes(StandardCharsets.US_ASCII));
        byte[] moov = box("moov", box("mvhd", mvhd));
        if (!moovLast) return concat(ftyp, moov, box("mdat", new byte[256]));
        byte[] data = new byte[256];
        byte[] largeMdat = ByteBuffer.allocate(16 + data.length).putInt(1).put("mdat".getBytes(StandardCharsets.US_ASCII))
                .putLong(16 + data.length).put(data).array();
        return concat(ftyp, largeMdat, moov);
    }

    private static byte[] mvhd0(long timescale, long duration) {
        return ByteBuffer.allocate(100).putInt(0).putInt(0).putInt(0).putInt((int) timescale).putInt((int) duration).array();
    }

    private static byte[] mvhd1(long timescale, long duration) {
        return ByteBuffer.allocate(112).putInt(1 << 24).putLong(0).putLong(0).putInt((int) timescale).putLong(duration).array();
    }

    /** 8비트 모노 WAV. declared가 0이 아니면 data 묶음 크기를 그 값으로 적음. */
    private static byte[] wav(int byteRate, int dataBytes, int declared) {
        byte[] fmt = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putShort((short) 1).putShort((short) 1)
                .putInt(byteRate).putInt(byteRate).putShort((short) 1).putShort((short) 8).array();
        byte[] data = new byte[dataBytes];
        byte[] dataChunk = chunk("data", data);
        if (declared != 0) ByteBuffer.wrap(dataChunk, 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(declared);
        byte[] list = chunk("LIST", new byte[3]);   // 홀수 크기 묶음은 한 바이트를 채워 건너뜀
        return concat("RIFF\0\0\0\0WAVE".getBytes(StandardCharsets.US_ASCII), chunk("fmt ", fmt), list, dataChunk);
    }

    private static byte[] box(String type, byte[] content) {
        return ByteBuffer.allocate(8 + content.length).putInt(8 + content.length)
                .put(type.getBytes(StandardCharsets.US_ASCII)).put(content).array();
    }

    private static byte[] chunk(String id, byte[] content) {
        int padded = content.length + (content.length & 1);
        return ByteBuffer.allocate(8 + padded).order(ByteOrder.LITTLE_ENDIAN).put(id.getBytes(StandardCharsets.US_ASCII))
                .putInt(content.length).put(content).array();
    }

    private static byte[] concat(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.writeBytes(part);
        return out.toByteArray();
    }

    private static void respond(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
