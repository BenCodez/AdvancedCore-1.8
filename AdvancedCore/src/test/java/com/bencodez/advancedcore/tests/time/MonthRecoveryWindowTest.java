package com.bencodez.advancedcore.tests.time;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.LocalDateTime;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.AdvancedCoreConfigOptions;
import com.bencodez.advancedcore.data.ServerData;
import com.bencodez.advancedcore.api.time.TimeChecker;
import com.bencodez.advancedcore.bungeeapi.time.BungeeTimeChecker;

class MonthRecoveryWindowTest {
    @Test void backendHonorsBoundaryAndBypass() {
        checkBackend(LocalDateTime.of(2026, 10, 1, 0, 0), false, true);
        checkBackend(LocalDateTime.of(2026, 10, 1, 12, 0), false, true);
        checkBackend(LocalDateTime.of(2026, 10, 1, 12, 0, 1), false, false);
        checkBackend(LocalDateTime.of(2026, 10, 2, 0, 0), false, false);
        checkBackend(LocalDateTime.of(2026, 10, 15, 0, 0), true, true);
    }
    @Test void proxyHonorsBoundaryAndBypass() {
        checkProxy(LocalDateTime.of(2026, 10, 1, 0, 0), false, true);
        checkProxy(LocalDateTime.of(2026, 10, 1, 12, 0), false, true);
        checkProxy(LocalDateTime.of(2026, 10, 1, 12, 0, 1), false, false);
        checkProxy(LocalDateTime.of(2026, 10, 2, 0, 0), false, false);
        checkProxy(LocalDateTime.of(2026, 10, 15, 0, 0), true, true);
    }
    private void checkBackend(LocalDateTime now, boolean bypass, boolean expected) {
        AdvancedCorePlugin plugin = mock(AdvancedCorePlugin.class);
        AdvancedCoreConfigOptions options = mock(AdvancedCoreConfigOptions.class);
        ServerData data = mock(ServerData.class);
        when(plugin.getOptions()).thenReturn(options);
        when(plugin.getServerDataFile()).thenReturn(data);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(options.isTimeChangeFailSafeBypass()).thenReturn(bypass);
        when(data.getPrevMonth()).thenReturn("SEPTEMBER");
        TimeChecker checker = spy(new TimeChecker(plugin));
        doReturn(now).when(checker).getTime();
        assertEquals(expected, checker.hasMonthChanged(false));
        if (!expected) verify(data).setPrevMonth("OCTOBER");
        else verify(data, never()).setPrevMonth(anyString());
        when(data.getPrevMonth()).thenReturn("OCTOBER");
        assertFalse(checker.hasMonthChanged(true));
    }
    private void checkProxy(LocalDateTime now, boolean bypass, boolean expected) {
        BungeeTimeChecker checker = mock(BungeeTimeChecker.class, CALLS_REAL_METHODS);
        checker.setTimeChangeFailSafeBypass(bypass);
        doReturn(now).when(checker).getTime();
        doReturn("SEPTEMBER").when(checker).getPrevMonth();
        assertEquals(expected, checker.hasMonthChanged(false));
        if (!expected) verify(checker).setPrevMonth("OCTOBER");
        else verify(checker, never()).setPrevMonth(anyString());
        doReturn("OCTOBER").when(checker).getPrevMonth();
        assertFalse(checker.hasMonthChanged(true));
    }
}
