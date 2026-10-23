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
    private static final String INSTRUCTIONS = PillOcrRules.INSTRUCTIONS;

    private final FileStorage fileStorage;
    private final OpenAiClient openAiClient;
    private final PillDrugIdentificationService identificationService;
    private final TemporaryPhotoCleanupService cleanup;
    private final org.springframework.core.task.AsyncTaskExecutor executor;
    private final long timeoutMs;

    public PillPhotoIdentificationService(FileStorage fileStorage,
                                          OpenAiClient openAiClient,
                                          PillDrugIdentificationService identificationService,
                                          TemporaryPhotoCleanupService cleanup,
                                          @org.springframework.beans.factory.annotation.Qualifier("applicationTaskExecutor") org.springframework.core.task.AsyncTaskExecutor executor,
                                          @org.springframework.beans.factory.annotation.Value("${photo.pill-timeout-ms:85000}") long timeoutMs) {
        if (timeoutMs < 1) throw new IllegalArgumentException("낱알 처리 시간은 양수여야 합니다.");
        this.executor = executor;
        this.timeoutMs = timeoutMs;
        this.fileStorage = fileStorage;
        this.openAiClient = openAiClient;
        this.identificationService = identificationService;
        this.cleanup = cleanup;
    }

    public PillPhotoIdentificationResponse identify(List<MultipartFile> images) {
        return identify(images, false);
    }

    public PillPhotoIdentificationResponse identify(List<MultipartFile> images, boolean onlyBack) {
        if (images == null || images.isEmpty() || images.size() > MAX_PHOTOS) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "알약 사진을 최소 1장, 최대 2장까지 올려 주세요.");
        }
        identificationService.requireCatalogReady();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs);

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

            // 각 사진을 독립적으로 판독한다. 모델이 여러 장의 앞·뒷면을 한 번에 처리하며
            // 한쪽 각인을 누락하거나 요청이 실패하는 일을 줄이고, 각 면의 단서를 명시적으로 보존한다.
            PillIdentification front = null;
            PillIdentification back = null;
            for (int i = 0; i < dataUrls.size(); i++) {
                boolean isBack = dataUrls.size() == 2 ? i == 1 : onlyBack;
                String dataUrl = dataUrls.get(i);
                PillIdentification side = toIdentification(PhotoIdentificationWorkflow.await(
                        executor.submit(() -> readImage(dataUrl, isBack, dataUrls.size() == 1)), deadline, timeoutMs));
                if (isBack) back = side;
                else front = side;
            }
            PillIdentification extracted = merge(front, back);
            if (!hasAttributes(extracted)) {
                throw new BusinessException(ErrorCode.INVALID_INPUT,
                        "알약 정보를 읽지 못했습니다. 알약 앞면이 선명하게 보이도록 다시 촬영해 주세요.");
            }

            List<PillIdentificationCandidate> candidates = PhotoIdentificationWorkflow.await(
                    executor.submit(() -> identificationService.identify(extracted)), deadline, timeoutMs);
            completed = true;
            return new PillPhotoIdentificationResponse(extracted, candidates);
        } finally {
            if (completed) cleanup.deleteAfterProcessing(savedNames);
            else cleanup.deleteNow(savedNames);
        }
    }

    private JsonNode readImage(String dataUrl, boolean back, boolean singleImage) {
        String side = back ? "사진2" : "사진1";
        String sideRules = " 제공한 사진은 " + side + "이며 공식 앞면·뒷면을 뜻하지 않습니다. "
                + "각인/분할선은 " + (back ? "printBack/lineBack" : "printFront/lineFront")
                + "에만 적고 반대쪽 값은 null, 상태는 UNREADABLE로 반환하세요.";
        String prompt = side + " 한 장에서 보이는 단서만 읽으세요. 반대 면이나 불확실한 글자는 추측하지 마세요.";
        return openAiClient.createJsonFromImages(
                INSTRUCTIONS + sideRules,
                prompt,
                List.of(dataUrl),
                1600,
                "pill_image_reading",
                SCHEMA);
    }

    private static PillIdentification merge(PillIdentification front, PillIdentification back) {
        return new PillIdentification(
                first(front == null ? null : front.itemName(), back == null ? null : back.itemName()),
                first(front == null ? null : front.entpName(), back == null ? null : back.entpName()),
                front == null ? null : front.printFront(),
                back == null ? null : back.printBack(),
                first(front == null ? null : front.shape(), back == null ? null : back.shape()),
                first(front == null ? null : front.colorClass1(), back == null ? null : back.colorClass1()),
                first(front == null ? null : front.colorClass2(), back == null ? null : back.colorClass2()),
                front == null ? null : front.lineFront(),
                back == null ? null : back.lineBack(),
                front == null ? PillIdentification.ObservationState.UNREADABLE : front.printFrontState(),
                back == null ? PillIdentification.ObservationState.UNREADABLE : back.printBackState(),
                front == null ? PillIdentification.ObservationState.UNREADABLE : front.lineFrontState(),
                back == null ? PillIdentification.ObservationState.UNREADABLE : back.lineBackState());
    }

    private static String first(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private static PillIdentification toIdentification(JsonNode output) {
        return PillOcrRules.read(output);
    }

    private static boolean hasAttributes(PillIdentification input) {
        return input.itemName() != null || input.entpName() != null || input.printFront() != null
                || input.printBack() != null || input.shape() != null || input.colorClass1() != null
                || input.colorClass2() != null || input.lineFront() != null || input.lineBack() != null;
    }

    private static Map<String, Object> schema() { return PillOcrRules.schema(); }
}
