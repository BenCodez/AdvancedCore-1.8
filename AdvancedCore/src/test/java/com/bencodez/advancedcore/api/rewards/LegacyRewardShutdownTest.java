package com.bencodez.advancedcore.api.rewards;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Timer;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class LegacyRewardShutdownTest {
    @Test void stopsRepeatAdmissionBeforeAwaitingAcceptedDelayedWorkWithoutInterruptingIt() throws Exception {
        Fixture f=new Fixture();when(f.delayed.awaitTermination(10,TimeUnit.SECONDS)).thenReturn(true);f.handler.shutdown();
        org.mockito.InOrder order=inOrder(f.repeat,f.delayed);order.verify(f.repeat).cancel();order.verify(f.delayed).shutdown();order.verify(f.delayed).awaitTermination(10,TimeUnit.SECONDS);
        verify(f.delayed,never()).shutdownNow();
    }
    @Test void unfinishedDelayedWorkIsReportedAndNeverForcedToLookCompleted() throws Exception {
        Fixture f=new Fixture();assertThrows(IllegalStateException.class,f.handler::shutdown);verify(f.delayed,never()).shutdownNow();
    }
    @Test void interruptionIsPreservedAndAcceptedWorkIsNotInterruptedByShutdownNow() throws Exception {
        Fixture f=new Fixture();when(f.delayed.awaitTermination(10,TimeUnit.SECONDS)).thenThrow(new InterruptedException("fixture"));
        try {assertThrows(IllegalStateException.class,f.handler::shutdown);assertTrue(Thread.currentThread().isInterrupted());verify(f.delayed,never()).shutdownNow();}
        finally {Thread.interrupted();}
    }
    static class Fixture {
        final RewardHandler handler=mock(RewardHandler.class,CALLS_REAL_METHODS);final Timer repeat=mock(Timer.class);final ScheduledExecutorService delayed=mock(ScheduledExecutorService.class);
        Fixture()throws Exception {set("repeatTimer",repeat);set("delayedTimer",delayed);}
        void set(String name,Object value)throws Exception {java.lang.reflect.Field field=RewardHandler.class.getDeclaredField(name);field.setAccessible(true);field.set(handler,value);}
    }
}
