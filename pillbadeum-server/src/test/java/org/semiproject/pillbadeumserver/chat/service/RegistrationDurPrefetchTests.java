package org.semiproject.pillbadeumserver.chat.service;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.drug.domain.DrugRegistered;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** COMMON-005: 등록 응답을 기다리게 하지 않는 제출, 동시 품목 중복 제거, 실패 후 복구를 확인한다. */
class RegistrationDurPrefetchTests {
    private final DurLookupService lookup = mock(DurLookupService.class);
    private final DurCacheStore cache = mock(DurCacheStore.class);
    private SimpleAsyncTaskExecutor executor;
    private RegistrationDurPrefetch prefetch;
    private CountDownLatch release;

    @BeforeEach void prepare() {
        executor = new SimpleAsyncTaskExecutor("registration-dur-test-");
        executor.setVirtualThreads(true);
        executor.setTaskTerminationTimeout(2000);
        prefetch = new RegistrationDurPrefetch(lookup, cache, executor);
        release = new CountDownLatch(1);
        when(lookup.collectForPrefetch(anyList(), anySet(), any())).thenReturn(List.of());
    }
    @AfterEach void close() { release.countDown(); executor.close(); }

    @Test void duplicateInFlightEventsShareOneLookupAndReturnBeforeItFinishes() throws Exception {
        var started = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        when(lookup.collectForPrefetch(anyList(), anySet(), any())).thenAnswer(invocation -> {
            started.countDown();
            assertThat(release.await(3, TimeUnit.SECONDS)).isTrue();
            return List.of();
        });
        doAnswer(invocation -> { finished.countDown(); return null; }).when(cache).refresh(anyList(), any());
        prefetch.registered(new DrugRegistered(List.of("A", "A")));
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        prefetch.registered(new DrugRegistered(List.of("A")));
        assertThat(finished.getCount()).isEqualTo(1);
        release.countDown();
        assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
        verify(lookup, times(1)).collectForPrefetch(eq(List.of("A")), eq(java.util.EnumSet.allOf(DurLookup.Type.class)), any());
    }
    @Test void rejectedSubmissionDoesNotEscapeAndReleasesTheInFlightClaim() {
        var rejecting = mock(AsyncTaskExecutor.class);
        doThrow(new RejectedExecutionException("synthetic")).when(rejecting).execute(any(Runnable.class));
        var service = new RegistrationDurPrefetch(lookup, cache, rejecting);
        assertDoesNotThrow(() -> service.registered(new DrugRegistered(List.of("A"))));
        assertDoesNotThrow(() -> service.registered(new DrugRegistered(List.of("A"))));
        verify(rejecting, times(2)).execute(any(Runnable.class));
        verifyNoInteractions(lookup, cache);
    }
    @Test void lookupFailureDoesNotInvokeCacheOrEscapeToRegistration() {
        when(lookup.collectForPrefetch(anyList(), anySet(), any())).thenThrow(new IllegalStateException("synthetic"));
        assertDoesNotThrow(() -> prefetch.registered(new DrugRegistered(List.of("A"))));
        verify(lookup, timeout(2000)).collectForPrefetch(anyList(), anySet(), any());
        executor.close();
        verifyNoInteractions(cache);
    }
    @Test void cacheFailureDoesNotEscapeToRegistration() {
        doThrow(new IllegalStateException("synthetic")).when(cache).refresh(anyList(), any());
        assertDoesNotThrow(() -> prefetch.registered(new DrugRegistered(List.of("A"))));
        verify(cache, timeout(2000)).refresh(anyList(), any());
    }
    @Test void emptyRegistrationDoesNotScheduleWork() {
        prefetch.registered(new DrugRegistered(List.of()));
        verifyNoInteractions(lookup, cache);
    }

    @Test void distinctRegistrationsDoNotStartAnotherBackgroundBatchWhileOneIsRunning() {
        var queued = new java.util.ArrayList<Runnable>();
        var controlled = mock(AsyncTaskExecutor.class);
        doAnswer(call -> { queued.add(call.getArgument(0)); return null; })
                .when(controlled).execute(any(Runnable.class));
        var service = new RegistrationDurPrefetch(lookup, cache, controlled);
        service.registered(new DrugRegistered(List.of("A")));
        service.registered(new DrugRegistered(List.of("B")));
        assertThat(queued).hasSize(1);
        queued.removeFirst().run();

        // A later registration may prefetch again after the first batch releases its permit.
        service.registered(new DrugRegistered(List.of("B")));
        assertThat(queued).hasSize(1);
        queued.removeFirst().run();
        verify(lookup, times(2)).collectForPrefetch(anyList(), anySet(), any());
    }
}
