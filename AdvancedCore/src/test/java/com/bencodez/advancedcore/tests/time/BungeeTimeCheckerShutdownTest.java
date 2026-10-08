package com.bencodez.advancedcore.tests.time;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.lang.reflect.Field;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.Test;
import com.bencodez.advancedcore.bungeeapi.time.BungeeTimeChecker;

class BungeeTimeCheckerShutdownTest {
    @Test void shutdownBeforeTimerLoadingIsSafe() {
        BungeeTimeChecker checker = mock(BungeeTimeChecker.class, CALLS_REAL_METHODS);
        assertDoesNotThrow(checker::shutdown);
    }
    @Test void shutdownStopsAnInitializedTimer() throws Exception {
        BungeeTimeChecker checker = mock(BungeeTimeChecker.class, CALLS_REAL_METHODS);
        ScheduledExecutorService timer = mock(ScheduledExecutorService.class);
        Field field = BungeeTimeChecker.class.getDeclaredField("timer");
        field.setAccessible(true); field.set(checker, timer);
        checker.shutdown();
        verify(timer).shutdownNow();
    }
}
