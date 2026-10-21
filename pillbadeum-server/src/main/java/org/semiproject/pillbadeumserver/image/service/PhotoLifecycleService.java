package org.semiproject.pillbadeumserver.image.service;

import java.nio.file.*;
import java.time.*;
import java.util.List;
import org.semiproject.pillbadeumserver.common.file.FileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;

/** REG-004/005: 파일 정리 실패는 저장 성공을 취소하지 않는다. 남긴 행/고아 파일은 만료 작업이 재확인한다. */
@Service
public class PhotoLifecycleService {
  private static final Logger log = LoggerFactory.getLogger(PhotoLifecycleService.class);
  private final ImageKeywordRegistrationService store;
  private final TemporaryPhotoCleanupService cleanup;
  private final FileStorage files;
  private final PillIdentificationSessions sessions;

  @Value("${photo.cleanup.enabled:true}")
  private boolean enabled = true;

  public PhotoLifecycleService(
      ImageKeywordRegistrationService store,
      TemporaryPhotoCleanupService cleanup,
      FileStorage files, PillIdentificationSessions sessions) {
    this.store = store;
    this.cleanup = cleanup;
    this.files = files;
    this.sessions = sessions;
  }

  public void deleteOwned(long photoId) {
    var photo = store.owned(photoId, false);
    if (photo.kind() == org.semiproject.pillbadeumserver.image.domain.ImagePhotoKind.PILL)
      sessions.cancelPhoto(photo.id());
    deleteGroup(photo.id());
  }

  public void deleteGroup(long rootId) {
    var members = store.members(rootId);
    if (members.isEmpty()) return;
    var result =
        cleanup.deleteNow(
            members.stream().map(ImageKeywordRegistrationService.Photo::path).toList());
    if (result.failed() == 0) store.deleteRows(rootId);
  }

  public void failedUpload(List<String> names, List<Long> ids) {
    try {
      var result = cleanup.deleteNow(names);
      if (result.failed() == 0 && !ids.isEmpty()) store.deleteRows(ids.getFirst());
    } catch (RuntimeException failure) {
      // 원래의 식별 실패를 정리 오류로 덮지 않는다. 남은 행은 만료 작업이 재시도한다.
      log.warn("[사진 정리] failed_upload=deferred type={}", failure.getClass().getSimpleName());
    }
  }

  @Transactional
  public void removeForProfile(long profileId) {
    var names = store.profileFileNames(profileId);
    store.deleteProfileRows(profileId);
    if (names.isEmpty()) return;
    // 프로필 DB 삭제가 롤백되면 원본을 유지한다. 커밋 뒤 IO 실패 파일은 고아 파일 정리가 재시도한다.
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            cleanup.deleteNow(names);
          }
        });
  }

  @Scheduled(
      fixedDelayString = "${photo.cleanup.interval-ms:600000}",
      initialDelayString = "${photo.cleanup.interval-ms:600000}")
  public void removeExpired() {
    if (!enabled) return;
    try {
      sessions.removeExpired();
    } catch (RuntimeException error) {
      log.warn("[식별 결과 정리] result=failed type={}", error.getClass().getSimpleName());
    }
    for (long id : store.expiredRoots()) {
      try {
        deleteGroup(id);
      } catch (RuntimeException error) {
        log.warn("[사진 정리] result=failed type={}", error.getClass().getSimpleName());
      }
    }
    // DB 저장 전 중단/삭제 커밋 뒤 IO 실패로 남은 파일도 해당 임시 폴더 안에서만 정리한다.
    Path folder = Path.of(files.getBasePath(), FileStorage.DIR_TEMP);
    if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) return;
    var referenced = store.referencedFiles();
    try (var paths = Files.newDirectoryStream(folder)) {
      Instant cutoff = Instant.now().minus(Duration.ofMinutes(30));
      for (Path path : paths)
        if (!referenced.contains(path.getFileName().toString())
            && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
            && Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS)
                .toInstant()
                .isBefore(cutoff)) cleanup.deleteNow(List.of(path.getFileName().toString()));
    } catch (java.io.IOException error) {
      log.warn("[사진 정리] orphan_scan=failed type={}", error.getClass().getSimpleName());
    }
  }
}
