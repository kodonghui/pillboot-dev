package org.semiproject.pillbadeumserver.image.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semiproject.pillbadeumserver.common.file.FileStorage;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

class TemporaryPhotoCleanupServiceTests {
    @TempDir Path root;
    FileStorage files;
    TemporaryPhotoCleanupService cleanup;

    @BeforeEach
    void setUp() {
        files = new FileStorage();
        ReflectionTestUtils.setField(files, "windowsPath", root.toString());
        ReflectionTestUtils.setField(files, "macPath", root.toString());
        cleanup = new TemporaryPhotoCleanupService(files);
    }

    @Test
    void removesOnlyRequestedTemporaryFilesAndRepeatedDeletionIsHarmless() throws Exception {
        String first = save(), second = save(), untouched = save();
        Path profileCopy = root.resolve(FileStorage.DIR_PROFILE).resolve(first);
        Files.createDirectories(profileCopy.getParent());
        Files.writeString(profileCopy, "keep");

        assertThat(cleanup.deleteNow(List.of(first, first, second)))
                .isEqualTo(new TemporaryPhotoCleanupService.Result(2, 0, 0));
        assertThat(cleanup.deleteNow(List.of(first, second)))
                .isEqualTo(new TemporaryPhotoCleanupService.Result(0, 2, 0));
        assertThat(path(untouched)).exists();
        assertThat(profileCopy).hasContent("keep");
        assertThat(root.resolve(FileStorage.DIR_TEMP)).isDirectory();
    }

    @Test
    void rejectsTraversalAbsolutePathsAndDirectoriesWithoutDeletingThem() throws Exception {
        String name = save();
        Path directory = path("a".repeat(32) + ".jpg");
        Files.createDirectory(directory);
        List<String> invalid = Arrays.asList("../" + name, "..\\" + name,
                path(name).toString(), "img_temp/" + name, "", null, "original.jpg", directory.getFileName().toString());

        assertThat(cleanup.deleteNow(invalid).failed()).isEqualTo(invalid.size());
        assertThat(path(name)).exists();
        assertThat(directory).isDirectory();
    }

    @Test
    void missingTemporaryDirectoryIsNotAnError() {
        assertThat(cleanup.deleteNow(List.of("a".repeat(32) + ".jpg")))
                .isEqualTo(new TemporaryPhotoCleanupService.Result(0, 1, 0));
    }

    @Test
    void doesNotDeleteSymbolicLinksOrTheirTargets() throws Exception {
        String target = save();
        Path link = path("b".repeat(32) + ".jpg");
        createLinkOrSkip(link, path(target));

        assertThat(files.deleteTemporaryImage(link.getFileName().toString())).isEqualTo(FileStorage.DeleteResult.FAILED);
        assertThat(Files.isSymbolicLink(link)).isTrue();
        assertThat(path(target)).exists();
    }

    @Test
    void rejectsTemporaryDirectoryRedirectedToAnotherFolder() throws Exception {
        Path other = Files.createDirectory(root.resolve("other"));
        String name = "c".repeat(32) + ".png";
        Files.writeString(other.resolve(name), "keep");
        createLinkOrSkip(root.resolve(FileStorage.DIR_TEMP), other);

        assertThat(files.deleteTemporaryImage(name)).isEqualTo(FileStorage.DeleteResult.FAILED);
        assertThat(other.resolve(name)).hasContent("keep");
    }

    @Test
    void deletesOnlyAfterCommitAndUsesSnapshotOfTheFileList() {
        String first = save(), untouched = save();
        var names = new ArrayList<>(List.of(first));
        var manager = new TestTransactions(() -> assertThat(path(first)).exists());

        new TransactionTemplate(manager).executeWithoutResult(status -> {
            cleanup.deleteAfterCommit(names);
            names.clear();
            names.add(untouched);
            assertThat(path(first)).exists();
        });

        assertThat(manager.committed).isTrue();
        assertThat(path(first)).doesNotExist();
        assertThat(path(untouched)).exists();
    }

    @Test
    void rollbackKeepsFiles() {
        String name = save();
        var manager = new TestTransactions(() -> {});
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            cleanup.deleteAfterCommit(List.of(name));
            status.setRollbackOnly();
        });
        assertThat(manager.committed).isFalse();
        assertThat(path(name)).exists();
    }

    @Test
    void failedCommitKeepsFiles() {
        String name = save();
        var manager = new TestTransactions(() -> { throw new IllegalStateException("commit failed"); });
        assertThatThrownBy(() -> new TransactionTemplate(manager).executeWithoutResult(status ->
                cleanup.deleteAfterCommit(List.of(name)))).isInstanceOf(IllegalStateException.class);
        assertThat(path(name)).exists();
    }

    @Test
    void activeTransactionWithoutSynchronizationNeverDeletesBeforeCommit() {
        String name = save();
        var manager = new TestTransactions(() -> assertThat(path(name)).exists());
        manager.setTransactionSynchronization(AbstractPlatformTransactionManager.SYNCHRONIZATION_NEVER);
        new TransactionTemplate(manager).executeWithoutResult(status -> cleanup.deleteAfterCommit(List.of(name)));
        assertThat(path(name)).exists();
    }

    @Test
    void withoutTransactionCompletedWorkIsCleanedImmediately() {
        String name = save();
        cleanup.deleteAfterProcessing(List.of(name));
        assertThat(path(name)).doesNotExist();
    }

    @Test
    void saveCleanupWithoutCommitHookPreservesTheFile() {
        String name = save();
        cleanup.deleteAfterCommit(List.of(name));
        assertThat(path(name)).exists();
    }

    @Test
    void oneDeletionFailureDoesNotStopOtherFilesOrFailCommittedWork() {
        FileStorage failing = mock(FileStorage.class);
        when(failing.deleteTemporaryImage("first")).thenThrow(new IllegalStateException("disk failure"));
        when(failing.deleteTemporaryImage("second")).thenReturn(FileStorage.DeleteResult.DELETED);
        var service = new TemporaryPhotoCleanupService(failing);
        assertThat(service.deleteNow(List.of("first", "second")))
                .isEqualTo(new TemporaryPhotoCleanupService.Result(1, 0, 1));

        var manager = new TestTransactions(() -> {});
        assertThatCode(() -> new TransactionTemplate(manager).executeWithoutResult(status ->
                service.deleteAfterCommit(List.of("first")))).doesNotThrowAnyException();
        assertThat(manager.committed).isTrue();
    }

    private String save() {
        return files.saveImage(new MockMultipartFile("images", "image.png", "image/png", new byte[]{1, 2}), FileStorage.DIR_TEMP);
    }

    private Path path(String name) { return root.resolve(FileStorage.DIR_TEMP).resolve(name); }

    private static void createLinkOrSkip(Path link, Path target) throws IOException {
        try { Files.createSymbolicLink(link, target); }
        catch (IOException | UnsupportedOperationException | SecurityException e) {
            assumeTrue(false, "Symbolic-link creation is unavailable: " + e.getClass().getSimpleName());
        }
    }

    /** Real Spring commit/rollback synchronization lifecycle, without a business DB or manual callback invocation. */
    static class TestTransactions extends AbstractPlatformTransactionManager {
        private final Runnable beforeCommit;
        boolean committed;
        TestTransactions(Runnable beforeCommit) { this.beforeCommit = beforeCommit; }
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {}
        @Override protected void doCommit(DefaultTransactionStatus status) { beforeCommit.run(); committed = true; }
        @Override protected void doRollback(DefaultTransactionStatus status) {}
    }
}
