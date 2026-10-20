package org.semiproject.pillbadeumserver.medication.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.ObjectMapper;

import java.net.http.WebSocket;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class MedicationLiveUsageTests {
    private final ObjectMapper json = new ObjectMapper();
    private final WebSocket socket = mock(WebSocket.class);
    private final AtomicInteger disconnected = new AtomicInteger();
    private final MedicationLiveClient.SocketListener listener = new MedicationLiveClient.SocketListener(
            json, ignored -> {}, disconnected::incrementAndGet, "gpt-live-1", "live_fixture");

    @Test
    void logsFinalDurationOnceWithoutAddingCumulativeSnapshots(CapturedOutput output) {
        update("12"); update("32.5"); update("12");
        finish("45.8"); finish("45.8");
        listener.onClose(socket, 1000, "");
        assertThat(output.getOut()).contains("model=gpt-live-1", "session=live_fixture", "사용 시간=45.8초",
                "종료 사유=close_requested", "음성 토큰 수는 API 미제공");
        assertThat(output.getAll().split("\\[OpenAiLive\\] 완료", -1)).hasSize(2);
        assertThat(output.getAll()).doesNotContain("최종 사용량 미확정");
    }

    @Test
    void retainsTheSidebandUntilFinalUsageArrivesAfterCloseWasRequested(CapturedOutput output) {
        listener.drainAndClose(socket);
        listener.drainAndClose(socket);
        verify(socket, never()).abort();
        finish("24");
        verify(socket, times(1)).abort();
        assertThat(output.getAll()).contains("사용 시간=24.0초").doesNotContain("최종 사용량 미확정");
    }

    @Test
    void droppedConnectionLogsLatestSnapshotAsUnconfirmedOnlyOnce(CapturedOutput output) {
        update("32.5"); update("12");
        listener.onError(socket, new IllegalStateException("secret transcript"));
        listener.onClose(socket, 1006, "secret transcript");
        listener.drainAndClose(socket);
        assertThat(output.getAll()).contains("최종 사용량 미확정", "마지막 누적 사용 시간=32.5초")
                .doesNotContain("[OpenAiLive] 완료", "secret transcript");
        assertThat(output.getAll().split("최종 사용량 미확정", -1)).hasSize(2);
        verify(socket).abort();
    }

    @Test
    void missingUsageIsUnknownInsteadOfZero(CapturedOutput output) {
        listener.onClose(socket, 1000, "");
        assertThat(output.getAll()).contains("최종 사용량 미확정", "마지막 누적 사용 시간=unknown초")
                .doesNotContain("사용 시간=0.0초", "[OpenAiLive] 완료");
    }

    @Test
    void malformedFinalUsageDoesNotTurnAnEarlierSnapshotIntoAFinalValue(CapturedOutput output) {
        update("12.5"); finish("\"45.8\"");
        assertThat(output.getAll()).contains("최종 사용량 미확정", "마지막 누적 사용 시간=12.5초")
                .doesNotContain("[OpenAiLive] 완료");
    }

    @Test
    void negativeUsageIsIgnoredAndZeroFinalUsageIsValid(CapturedOutput output) {
        update("-10"); finish("0");
        assertThat(output.getAll()).contains("사용 시간=0.0초").doesNotContain("최종 사용량 미확정");
    }

    @Test
    void finalizationTimeoutReleasesTheSocketAndMarksUsageUnconfirmed(CapturedOutput output) {
        update("9.5");
        listener.drainAndClose(socket);
        verify(socket, timeout(4500)).abort();
        assertThat(output.getAll()).contains("최종 사용량 미확정", "마지막 누적 사용 시간=9.5초");
    }

    private void update(String seconds) {
        listener.onText(socket, "{\"type\":\"session.usage.updated\",\"usage\":{\"seconds\":" + seconds + "}}", true);
    }

    private void finish(String seconds) {
        listener.onText(socket, "{\"type\":\"session.closed\",\"reason\":\"close_requested\",\"usage\":{\"seconds\":" + seconds + "}}", true);
    }
}
