package org.semiproject.pillbadeumserver.image.service;

import org.semiproject.pillbadeumserver.common.ai.OpenAiClient;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.common.file.FileStorage;
import org.semiproject.pillbadeumserver.image.domain.PillIdentification;
import org.semiproject.pillbadeumserver.image.domain.PillIdentificationCandidate;
import org.semiproject.pillbadeumserver.image.domain.PillPhotoIdentificationResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 알약 사진을 임시 저장하고 각인/외형을 읽어 공공데이터 후보로 변환합니다. */
@Service
public class PillPhotoIdentificationService {

    private static final int MAX_PHOTOS = 2;
    private static final Map<String, Object> SCHEMA = schema();
    private static final String INSTRUCTIONS = """
            사진에 있는 알약의 식별 정보를 읽으세요. 사진이 알약이 아니면 모든 문자열 필드를 빈 문자열로 반환하세요.
            약품명과 제조사명은 사진에서 명확히 읽히는 경우에만 적고, 추측하지 마세요.
            인쇄 문자는 보이는 그대로 옮기고, 앞면과 뒷면은 업로드 순서(첫 번째 사진=앞면, 두 번째 사진=뒷면)를 따르세요.
            모양/색상은 이미지에서 확인 가능한 값만 기록하세요. 불확실한 값은 빈 문자열로 반환하세요.
            """;

    private final FileStorage fileStorage;
    private final OpenAiClient openAiClient;
    private final PillDrugIdentificationService identificationService;
    private final TemporaryPhotoCleanupService cleanup;

    public PillPhotoIdentificationService(FileStorage fileStorage,
                                          OpenAiClient openAiClient,
                                          PillDrugIdentificationService identificationService,
                                          TemporaryPhotoCleanupService cleanup) {
        this.fileStorage = fileStorage;
        this.openAiClient = openAiClient;
        this.identificationService = identificationService;
        this.cleanup = cleanup;
    }

    public PillPhotoIdentificationResponse identify(List<MultipartFile> images) {
        if (images == null || images.isEmpty() || images.size() > MAX_PHOTOS) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "알약 사진은 앞면과 뒷면을 최대 2장까지 올려 주세요.");
        }

        List<String> savedNames = new ArrayList<>();
        boolean completed = false;
        try {
            List<String> dataUrls = new ArrayList<>();
            for (MultipartFile image : images) {
                String savedName = fileStorage.saveImage(image, FileStorage.DIR_TEMP);
                savedNames.add(savedName);
                dataUrls.add(OpenAiClient.toDataUrl(
                        fileStorage.readBytes(savedName, FileStorage.DIR_TEMP),
                        fileStorage.getMimeType(savedName)));
            }

            JsonNode output = openAiClient.createJsonFromImages(
                    INSTRUCTIONS,
                    "알약 사진을 식별하고 다음 필드에 정보를 채우세요. 사진 순서는 업로드 순서입니다.",
                    dataUrls, 500, "pill_image_reading", SCHEMA);
            PillIdentification extracted = toIdentification(output);
            if (!hasAttributes(extracted)) {
                throw new BusinessException(ErrorCode.INVALID_INPUT,
                        "알약 정보를 읽지 못했습니다. 알약 앞면이 선명하게 보이도록 다시 촬영해 주세요.");
            }

            List<PillIdentificationCandidate> candidates = identificationService.identify(extracted);
            completed = true;
            return new PillPhotoIdentificationResponse(extracted, candidates);
        } finally {
            if (completed) cleanup.deleteAfterProcessing(savedNames);
            else cleanup.deleteNow(savedNames);
        }
    }

    private static PillIdentification toIdentification(JsonNode output) {
        return new PillIdentification(
                value(output, "itemName"),
                value(output, "entpName"),
                value(output, "printFront"),
                value(output, "printBack"),
                value(output, "shape"),
                value(output, "colorClass1"),
                value(output, "colorClass2"),
                value(output, "lineFront"),
                value(output, "lineBack"));
    }

    private static String value(JsonNode node, String key) {
        String value = node.path(key).asString("").strip();
        return value.isEmpty() ? null : value;
    }

    private static boolean hasAttributes(PillIdentification input) {
        return input.itemName() != null || input.entpName() != null || input.printFront() != null
                || input.printBack() != null || input.shape() != null || input.colorClass1() != null
                || input.colorClass2() != null || input.lineFront() != null || input.lineBack() != null;
    }

    private static Map<String, Object> schema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (String field : List.of("itemName", "entpName", "printFront", "printBack", "shape",
                "colorClass1", "colorClass2", "lineFront", "lineBack")) {
            properties.put(field, Map.of("type", "string"));
        }
        return Map.of("type", "object", "properties", properties,
                "required", List.copyOf(properties.keySet()), "additionalProperties", false);
    }
}
