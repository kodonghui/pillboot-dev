package org.semiproject.pillbadeumserver.image;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.common.ai.OpenAiClient;
import org.semiproject.pillbadeumserver.common.exception.GlobalExceptionHandler;
import org.semiproject.pillbadeumserver.common.external.PublicDataClient;
import org.semiproject.pillbadeumserver.common.file.FileStorage;
import org.semiproject.pillbadeumserver.image.controller.PillIdentificationController;
import org.semiproject.pillbadeumserver.image.service.*;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Same URLs and multipart fields as Swagger, with only external providers replaced by fixtures. */
class PillIdentificationHttpTests {
    final JsonMapper json = new JsonMapper();
    final PillCatalog catalog = mock(PillCatalog.class);
    final PublicDataClient publicData = mock(PublicDataClient.class);
    final OpenAiClient ai = mock(OpenAiClient.class);
    MockMvc http;

    @BeforeEach
    void setup() {
        List<JsonNode> rows = new ArrayList<>();
        for (int i = 1; i <= 5; i++) rows.add(json.valueToTree(Map.of("ITEM_SEQ", "90000" + i,
                "ITEM_NAME", "비슷한각인약" + i, "ENTP_NAME", "합성제약", "ETC_OTC_NAME", "일반의약품", "PRINT_FRONT", "DK", "PRINT_BACK", "20", "DRUG_SHAPE", "원형", "COLOR_CLASS1", "하양")));
        rows.add(json.valueToTree(Map.of("ITEM_SEQ", "197400040", "ITEM_NAME", "인데놀정10mg(프로프라놀롤염산염)",
                "ENTP_NAME", "합성제약", "ETC_OTC_NAME", "일반의약품", "PRINT_FRONT", "DK", "PRINT_BACK", "10", "DRUG_SHAPE", "원형", "COLOR_CLASS1", "하양")));
        when(catalog.rows()).thenReturn(rows);
        var service = new PillDrugIdentificationService(publicData, catalog);
        var files = mock(FileStorage.class);
        when(files.saveImage(any(), eq(FileStorage.DIR_TEMP))).thenReturn("front.png", "back.png");
        when(files.readBytes(anyString(), eq(FileStorage.DIR_TEMP))).thenReturn(new byte[]{1, 2, 3});
        when(files.getMimeType(anyString())).thenReturn("image/png");
        when(ai.createJsonFromImages(anyString(), anyString(), anyList(), anyInt(), anyString(), anyMap()))
                .thenAnswer(call -> json.readTree(call.<String>getArgument(1).contains("사진2")
                        ? "{\"printBack\":\"10\",\"shape\":\"원형\",\"colorClass1\":\"흰색\"}"
                        : "{\"printFront\":\"DK\",\"shape\":\"원형\",\"colorClass1\":\"흰색\"}"));
        var photo = new PillPhotoIdentificationService(files, ai, service, mock(TemporaryPhotoCleanupService.class), new org.springframework.core.task.SimpleAsyncTaskExecutor(), 85_000);
        http = MockMvcBuilders.standaloneSetup(new PillIdentificationController(service, mock(PillDrugRegistrationService.class),
                photo, mock(TemporaryPhotoUploadService.class), mock(PillIdentificationSessions.class),
                mock(PhotoCandidateLookup.class), mock(PhotoIdentificationWorkflow.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void jsonEndpointRanksByCluesWithNullNames() throws Exception {
        var response = http.perform(post("/api/images/identify").contentType(MediaType.APPLICATION_JSON).content("""
                {"itemName":null,"entpName":"null","printFront":"DK","printBack":"10","shape":"원형","colorClass1":"흰색"}
                """)).andExpect(status().isOk()).andReturn().getResponse();
        assertCandidates(json.readTree(response.getContentAsString(StandardCharsets.UTF_8)));
        verifyNoInteractions(ai, publicData);
    }

    @Test
    void multipartEndpointMergesBothSidesAndReturnsTheSameCandidates() throws Exception {
        var response = http.perform(multipart("/api/images/pill-photo/identify")
                .file(new MockMultipartFile("front", "front.png", "image/png", new byte[]{1,2,3}))
                .file(new MockMultipartFile("back", "back.png", "image/png", new byte[]{1,2,3})))
                .andExpect(status().isOk()).andReturn().getResponse();
        JsonNode body = json.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.path("extracted").path("printFront").asString()).isEqualTo("DK");
        assertThat(body.path("extracted").path("printBack").asString()).isEqualTo("10");
        assertCandidates(body.path("candidates"));
        verify(ai, times(2)).createJsonFromImages(anyString(), anyString(), anyList(), anyInt(), anyString(), anyMap());
        verifyNoInteractions(publicData);
    }

    void assertCandidates(JsonNode rows) {
        assertThat(rows.size()).isEqualTo(1);
        assertThat(rows.path(0).path("itemSeq").asString()).isEqualTo("197400040");
        assertThat(rows.path(0).path("itemName").asString()).isEqualTo("인데놀정10mg(프로프라놀롤염산염)");
        assertThat(rows.path(0).path("similarityScore").isNull()).isTrue();
        assertThat(rows.path(0).path("matchDetails")).isNotEmpty();
    }
}
