package org.semiproject.pillbadeumserver.image.service;

import org.semiproject.pillbadeumserver.common.ai.OpenAiClient;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.common.file.FileStorage;
import org.semiproject.pillbadeumserver.drug.dto.DrugSearchResponse;
import org.semiproject.pillbadeumserver.drug.service.DrugSearchService;
import org.semiproject.pillbadeumserver.drug.service.ProfileAccessChecker;
import org.semiproject.pillbadeumserver.image.domain.PrescriptionPhotoIdentificationResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 처방전/약봉투 사진의 약명을 읽어 공공데이터 제품명 검색 후보를 제공합니다. */
@Service
public class PrescriptionPhotoIdentificationService {

    private static final int MAX_PHOTOS = 3;
    private static final int MAX_MEDICINE_NAMES = 10;
    private static final Map<String, Object> SCHEMA = schema();
    private static final String INSTRUCTIONS = """
            처방전 또는 약봉투 사진에서 처방/조제된 의약품의 제품명을 읽으세요.
            환자명, 생년월일, 주소, 병원명, 처방 날짜 등 개인정보는 읽거나 반환하지 마세요.
            약품명을 확신할 수 없는 항목은 제외하고, 같은 제품명은 한 번만 반환하세요.
            """ + PrescriptionOcrRules.PRODUCT_NAME;

    private final FileStorage fileStorage;
    private final OpenAiClient openAiClient;
    private final DrugSearchService drugSearchService;
    private final ProfileAccessChecker profileAccessChecker;
    private final TemporaryPhotoCleanupService cleanup;

    public PrescriptionPhotoIdentificationService(FileStorage fileStorage,
                                                   OpenAiClient openAiClient,
                                                   DrugSearchService drugSearchService,
                                                   ProfileAccessChecker profileAccessChecker,
                                                   TemporaryPhotoCleanupService cleanup) {
        this.fileStorage = fileStorage;
        this.openAiClient = openAiClient;
        this.drugSearchService = drugSearchService;
        this.profileAccessChecker = profileAccessChecker;
        this.cleanup = cleanup;
    }

    public PrescriptionPhotoIdentificationResponse identify(Long profileId, List<MultipartFile> images) {
        profileAccessChecker.checkOwner(profileId);
        if (images == null || images.isEmpty() || images.size() > MAX_PHOTOS) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "처방전/약봉투 사진은 최대 3장까지 올려 주세요.");
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
                    "사진에서 약 제품명만 읽어 medicines 배열로 반환하세요.",
                    dataUrls, 800, "prescription_medicine_names", SCHEMA);
            Set<String> names = readNames(output);
            if (names.isEmpty()) {
                throw new BusinessException(ErrorCode.INVALID_INPUT,
                        "사진에서 약 이름을 읽지 못했습니다. 글자가 선명하게 보이도록 다시 촬영해 주세요.");
            }

            List<PrescriptionPhotoIdentificationResponse.MedicineCandidates> medicines = names.stream()
                    .limit(MAX_MEDICINE_NAMES)
                    .map(name -> new PrescriptionPhotoIdentificationResponse.MedicineCandidates(
                            name, search(name)))
                    .toList();
            var response = new PrescriptionPhotoIdentificationResponse(medicines);
            completed = true;
            return response;
        } finally {
            if (completed) cleanup.deleteAfterProcessing(savedNames);
            else cleanup.deleteNow(savedNames);
        }
    }

    private List<DrugSearchResponse> search(String name) {
        try {
            return drugSearchService.searchPrescription(name);
        } catch (BusinessException exception) {
            // 너무 짧거나 비정상적으로 읽힌 문자열은 나머지 약 검색을 중단하지 않습니다.
            if (exception.getErrorCode() == ErrorCode.INVALID_INPUT) {
                return List.of();
            }
            throw exception;
        }
    }

    private static Set<String> readNames(JsonNode output) {
        Set<String> names = new LinkedHashSet<>();
        JsonNode medicines = output.path("medicines");
        if (medicines.isArray()) {
            for (JsonNode medicine : medicines) {
                String name = medicine.path("readName").asString("").strip();
                if (name.length() >= DrugSearchService.MIN_NAME_LENGTH
                        && name.length() <= DrugSearchService.MAX_NAME_LENGTH) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    private static Map<String, Object> schema() {
        Map<String, Object> medicineProperties = Map.of("readName", Map.of("type", "string"));
        Map<String, Object> medicine = Map.of("type", "object", "properties", medicineProperties,
                "required", List.of("readName"), "additionalProperties", false);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("medicines", Map.of("type", "array", "items", medicine));
        return Map.of("type", "object", "properties", properties,
                "required", List.of("medicines"), "additionalProperties", false);
    }
}
