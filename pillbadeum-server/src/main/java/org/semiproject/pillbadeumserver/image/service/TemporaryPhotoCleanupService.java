package org.semiproject.pillbadeumserver.image.service;

import org.semiproject.pillbadeumserver.common.file.FileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.List;

/** REQ-18: 서버가 저장한 파일명만 내부 서비스에서 전달한다. 클라이언트용 삭제 API가 아니다. */
@Service
public class TemporaryPhotoCleanupService {
    private static final Logger log = LoggerFactory.getLogger(TemporaryPhotoCleanupService.class);
    private final FileStorage files;

    public TemporaryPhotoCleanupService(FileStorage files) { this.files = files; }

    public record Result(int deleted, int missing, int failed) {}

    /**
     * 저장 TX 안에서 호출한다. 커밋 성공 뒤 삭제하고 롤백 때는 파일을 보존한다.
     * 커밋을 관찰할 수 없는 호출은 파일을 보존한다. 저장 서비스가 이미 반환/커밋했으면 deleteNow를 쓴다.
     */
    public void deleteAfterCommit(Collection<String> savedNames) {
        List<String> names = snapshot(savedNames);
        if (names.isEmpty()) return;
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            log.warn("[임시 이미지 정리] result=kept_without_commit_hook files={}", names.size());
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() { deleteNow(names); }
        });
    }

    /**
     * 현재 DB 쓰기가 없는 식별 경로용. 바깥의 일반 Spring TX가 있으면 커밋을 기다린다.
     * DB 저장을 추가할 때는 저장 TX 안에서 deleteAfterCommit를 직접 호출해 완료 경계를 명시한다.
     */
    public void deleteAfterProcessing(Collection<String> savedNames) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) deleteAfterCommit(savedNames);
        else deleteNow(savedNames);
    }

    /** 업로드 일부 실패·식별 실패/시간 초과로 더 사용하지 않는 파일을 즉시 정리한다. */
    public Result deleteNow(Collection<String> savedNames) {
        int deleted = 0, missing = 0, failed = 0;
        List<String> names = snapshot(savedNames);
        for (String name : names) {
            try {
                switch (files.deleteTemporaryImage(name)) {
                    case DELETED -> deleted++;
                    case NOT_FOUND -> missing++;
                    case FAILED -> failed++;
                }
            } catch (RuntimeException e) {
                // 원래 식별 예외를 가리지 않고 다음 파일도 처리한다. 파일명/경로/응답 원문은 로그에 넣지 않는다.
                failed++;
                log.warn("[임시 이미지 정리] file_result=failed type={}", e.getClass().getSimpleName());
            }
        }
        if (!names.isEmpty()) log.info("[임시 이미지 정리] deleted={} missing={} failed={}", deleted, missing, failed);
        return new Result(deleted, missing, failed);
    }

    private static List<String> snapshot(Collection<String> names) {
        return names == null ? List.of() : names.stream().distinct().toList();
    }
}
