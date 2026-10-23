package org.semiproject.pillbadeumserver.image.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semiproject.pillbadeumserver.common.ai.OpenAiClient;
import org.semiproject.pillbadeumserver.common.exception.InvalidFileException;
import org.semiproject.pillbadeumserver.common.file.FileStorage;
import org.semiproject.pillbadeumserver.drug.service.DrugSearchService;
import org.semiproject.pillbadeumserver.drug.service.ProfileAccessChecker;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual temporary files, mock model/public-data calls. Never reads a user's uploads or calls paid APIs. */
class TemporaryPhotoFlowTests {
    @TempDir Path root;
    FileStorage files;
    TemporaryPhotoCleanupService cleanup;
    OpenAiClient ai;
    ProfileAccessChecker access;
    final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        files = new FileStorage();
        ReflectionTestUtils.setField(files, "windowsPath", root.toString());
        ReflectionTestUtils.setField(files, "macPath", root.toString());
        cleanup = new TemporaryPhotoCleanupService(files);
        ai = mock(OpenAiClient.class);
        access = mock(ProfileAccessChecker.class);
    }

    @Test
    void pillIdentificationReadsBothImagesAndCleansThemAfterSuccess() throws Exception {
        var lookup = mock(PillDrugIdentificationService.class);
        when(ai.createJsonFromImages(anyString(), anyString(), anyList(), anyInt(), anyString(), anyMap()))
                .thenAnswer(call -> {
                    assertThat(temporaryCount()).isEqualTo(2);
                    assertThat(call.<List<String>>getArgument(2)).hasSize(1);
                    return json.readTree(call.<String>getArgument(1).contains("사진2")
                            ? "{\"printBack\":\"B\"}" : "{\"printFront\":\"A\"}");
                });
        when(lookup.identify(any())).thenReturn(List.of());
        var service = new PillPhotoIdentificationService(files, ai, lookup, cleanup, new org.springframework.core.task.SimpleAsyncTaskExecutor(), 85_000);

        var result = service.identify(List.of(image(), image()));

        assertThat(result.extracted().printFront()).isEqualTo("A");
        assertThat(result.extracted().printBack()).isEqualTo("B");
        verify(ai, times(2)).createJsonFromImages(anyString(), anyString(), anyList(), anyInt(), anyString(), anyMap());
        assertThat(temporaryCount()).isZero();
        assertThat(root.resolve(FileStorage.DIR_PILL)).doesNotExist();
    }

    @Test
    void emptyCatalogFailsBeforeSavingImagesOrCallingTheModel() {
        var lookup = mock(PillDrugIdentificationService.class);
        doThrow(new org.semiproject.pillbadeumserver.common.exception.BusinessException(
                org.semiproject.pillbadeumserver.common.exception.ErrorCode.PILL_CATALOG_PREPARING, "준비 중"))
                .when(lookup).requireCatalogReady();
        try (var executor = new org.springframework.core.task.SimpleAsyncTaskExecutor()) {
            var service = new PillPhotoIdentificationService(files, ai, lookup, cleanup, executor, 85_000);
            assertThatThrownBy(() -> service.identify(List.of(image(), image())))
                    .isInstanceOf(org.semiproject.pillbadeumserver.common.exception.BusinessException.class);
            verifyNoInteractions(ai);
            assertThat(temporaryCount()).isZero();
        }
    }

    @Test
    void bothSidesShareOneBudgetAndTimedOutPhotosAreRemoved() throws Exception {
        var lookup = mock(PillDrugIdentificationService.class);
        when(ai.createJsonFromImages(anyString(), anyString(), anyList(), anyInt(), anyString(), anyMap()))
                .thenAnswer(call -> { Thread.sleep(150); return json.readTree("{\"printFront\":\"A\"}"); });
        try (var executor = new org.springframework.core.task.SimpleAsyncTaskExecutor()) {
            var service = new PillPhotoIdentificationService(files, ai, lookup, cleanup, executor, 230);
            assertThatThrownBy(() -> service.identify(List.of(image(), image())))
                    .isInstanceOf(org.semiproject.pillbadeumserver.common.exception.ExternalApiException.class);
            verify(lookup, never()).identify(any());
            assertThat(temporaryCount()).isZero();
        }
    }

    @Test
    void completedIdentificationWaitsForCallerCommitAndRollbackPreservesFiles() throws Exception {
        when(ai.createJsonFromImages(anyString(), anyString(), anyList(), anyInt(), anyString(), anyMap()))
                .thenReturn(json.readTree("{\"printFront\":\"A\"}"));
        var lookup = mock(PillDrugIdentificationService.class);
        when(lookup.identify(any())).thenReturn(List.of());
        var service = new PillPhotoIdentificationService(files, ai, lookup, cleanup, new org.springframework.core.task.SimpleAsyncTaskExecutor(), 85_000);
        var manager = new TemporaryPhotoCleanupServiceTests.TestTransactions(() -> assertThat(temporaryCount()).isEqualTo(2));

        new TransactionTemplate(manager).executeWithoutResult(status -> {
            service.identify(List.of(image(), image()));
            assertThat(temporaryCount()).isEqualTo(2);
        });
        assertThat(temporaryCount()).isZero();

        new TransactionTemplate(manager).executeWithoutResult(status -> {
            service.identify(List.of(image(), image()));
            status.setRollbackOnly();
        });
        assertThat(temporaryCount()).isEqualTo(2);
    }

    @Test
    void modelFailureCleansBothPillImagesWithoutReplacingTheOriginalError() {
        var failure = new IllegalStateException("upstream unavailable");
        when(ai.createJsonFromImages(anyString(), anyString(), anyList(), anyInt(), anyString(), anyMap())).thenThrow(failure);
        var service = new PillPhotoIdentificationService(files, ai, mock(PillDrugIdentificationService.class), cleanup, new org.springframework.core.task.SimpleAsyncTaskExecutor(), 85_000);

        assertThatThrownBy(() -> service.identify(List.of(image(), image()))).isSameAs(failure);
        assertThat(temporaryCount()).isZero();
    }

    @Test
    void prescriptionIdentificationUsesSharedTemporaryDirectoryAndCleansAfterSearch() throws Exception {
        when(ai.createJsonFromImages(anyString(), anyString(), anyList(), anyInt(), anyString(), anyMap()))
                .thenReturn(json.readTree("{\"medicines\":[{\"readName\":\"테스트약\"}]}"));
        var search = mock(DrugSearchService.class);
        when(search.searchPrescription("테스트약")).thenAnswer(call -> {
            assertThat(temporaryCount()).isEqualTo(1);
            return List.of();
        });
        var service = new PrescriptionPhotoIdentificationService(files, ai, search, access, cleanup);

        var result = service.identify(16L, List.of(image()));

        assertThat(result.medicines()).hasSize(1);
        verify(access).checkOwner(16L);
        assertThat(temporaryCount()).isZero();
        assertThat(root.resolve(FileStorage.DIR_PRESCRIPTION)).doesNotExist();
    }

    @Test
    void prescriptionSearchFailureAlsoRemovesTemporaryImages() throws Exception {
        when(ai.createJsonFromImages(anyString(), anyString(), anyList(), anyInt(), anyString(), anyMap()))
                .thenReturn(json.readTree("{\"medicines\":[{\"readName\":\"테스트약\"}]}"));
        var search = mock(DrugSearchService.class);
        var failure = new IllegalStateException("lookup unavailable");
        when(search.searchPrescription(anyString())).thenThrow(failure);
        var service = new PrescriptionPhotoIdentificationService(files, ai, search, access, cleanup);
        assertThatThrownBy(() -> service.identify(16L, List.of(image()))).isSameAs(failure);
        assertThat(temporaryCount()).isZero();
    }

    @Test
    void uploadSuccessRetainsFilesForTheNextStage() {
        var service = new TemporaryPhotoUploadService(files, access, cleanup);
        var response = service.upload(16L, List.of(image(), image()));
        verify(access).checkOwner(16L);
        assertThat(response.photoIds()).hasSize(2);
        assertThat(temporaryCount()).isEqualTo(2);
        response.photoIds().forEach(name -> assertThat(root.resolve(FileStorage.DIR_TEMP).resolve(name)).exists());
    }

    @Test
    void partiallyFailedUploadRemovesOnlyItsOwnFiles() {
        String untouched = files.saveImage(image(), FileStorage.DIR_TEMP);
        var service = new TemporaryPhotoUploadService(files, access, cleanup);
        var invalid = new MockMultipartFile("images", "bad.txt", "text/plain", new byte[]{1});
        assertThatThrownBy(() -> service.upload(16L, List.of(image(), invalid))).isInstanceOf(InvalidFileException.class);
        assertThat(temporaryCount()).isEqualTo(1);
        assertThat(root.resolve(FileStorage.DIR_TEMP).resolve(untouched)).exists();
    }

    @Test
    void failedTransferAlsoRemovesThePartiallyWrittenFileBeforeReturningItsName() throws Exception {
        String untouched = files.saveImage(image(), FileStorage.DIR_TEMP);
        MultipartFile broken = mock(MultipartFile.class);
        when(broken.getOriginalFilename()).thenReturn("broken.png");
        when(broken.getContentType()).thenReturn("image/png");
        doAnswer(call -> {
            Files.write(call.getArgument(0, Path.class), new byte[]{1});
            throw new IOException("fixture transfer failed");
        }).when(broken).transferTo(any(Path.class));
        var service = new TemporaryPhotoUploadService(files, access, cleanup);

        assertThatThrownBy(() -> service.upload(16L, List.of(image(), broken)))
                .isInstanceOf(UncheckedIOException.class);
        assertThat(temporaryCount()).isEqualTo(1);
        assertThat(root.resolve(FileStorage.DIR_TEMP).resolve(untouched)).exists();
    }

    private MultipartFile image() {
        return new MockMultipartFile("images", "source.png", "image/png", new byte[]{1, 2, 3});
    }

    private long temporaryCount() {
        Path folder = root.resolve(FileStorage.DIR_TEMP);
        if (!Files.exists(folder)) return 0;
        try (var stream = Files.list(folder)) { return stream.count(); }
        catch (Exception e) { throw new AssertionError(e); }
    }
}
