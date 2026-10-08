package com.bencodez.advancedcore.tests.proxy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.ArrayList;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import com.bencodez.simpleapi.servercomm.global.GlobalMessageListener;
import com.bencodez.simpleapi.servercomm.global.GlobalMessageProxyHandler;

class LegacyGlobalMessageProxyHandlerTest {
    @Test void legacyDispatchPreservesPayloadIdentityAndChannelMatching() {
        ArrayList<String> sent = new ArrayList<>();
        GlobalMessageProxyHandler handler = new GlobalMessageProxyHandler() {
            public void sendMessage(String server, String channel, String... data) {
                sent.add(server);
                sent.add(channel);
                sent.addAll(Arrays.asList(data));
            }
        };
        GlobalMessageListener vote = mock(GlobalMessageListener.class);
        GlobalMessageListener login = mock(GlobalMessageListener.class);
        when(vote.getSubChannel()).thenReturn("Vote");
        when(login.getSubChannel()).thenReturn("Login");
        handler.addListener(vote);
        handler.addListener(login);
        ArrayList<String> data = new ArrayList<>(Arrays.asList("Player", "uuid", "service", "42"));
        handler.onMessage("vOtE", data);
        verify(vote).onReceive(same(data));
        verify(login, never()).onReceive(any());
        handler.sendMessage("backend-a", "Vote", data.toArray(new String[0]));
        assertEquals(Arrays.asList("backend-a", "Vote", "Player", "uuid", "service", "42"), sent);
    }
}
