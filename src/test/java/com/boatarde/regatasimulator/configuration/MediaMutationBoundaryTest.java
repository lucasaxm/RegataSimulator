package com.boatarde.regatasimulator.configuration;

import com.boatarde.regatasimulator.application.MediaMutationGuard;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MediaMutationBoundaryTest {
    @Test void snapshotWaitsForWriterAndBlocksEveryNestedMutationUntilReleased() throws Exception {
        var guard = new MediaMutationGuard();
        var entered = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var mutation = guard.mutation();
            Future<?> snapshot = pool.submit(() -> { try (var lease = guard.snapshot()) { entered.countDown(); } });
            assertFalse(entered.await(50, TimeUnit.MILLISECONDS));
            assertThrows(IllegalStateException.class, guard::snapshot);
            mutation.close();
            snapshot.get(2, TimeUnit.SECONDS);
            var lease = guard.snapshot();
            Future<?> writer;
            try {
                writer = pool.submit(() -> { try (var nested = guard.mutation()) { assertNotNull(nested); } });
                assertThrows(TimeoutException.class, () -> writer.get(50, TimeUnit.MILLISECONDS));
            } finally { lease.close(); }
            writer.get(2, TimeUnit.SECONDS);
        }
    }

    @Test void exceptionReleasesBoundary() throws Throwable {
        var guard = new MediaMutationGuard();
        var call = mock(ProceedingJoinPoint.class);
        when(call.proceed()).thenThrow(new IllegalStateException());
        assertThrows(IllegalStateException.class, () -> new MediaMutationBoundary(guard).invoke(call));
        try (var snapshot = guard.snapshot()) { assertNotNull(snapshot); }
    }
}