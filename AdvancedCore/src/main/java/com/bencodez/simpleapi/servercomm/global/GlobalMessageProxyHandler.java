package com.bencodez.simpleapi.servercomm.global;

import java.util.ArrayList;

/**
 * Legacy 1.8 proxy API retained from SimpleAPI commit
 * 088b751bdc3ed50b74315aa64c4308a83288ca6a. The released 0.0.7
 * dependency replaced sendMessage with a delay-bearing signature; the 1.8
 * fork retains its original immediate-delivery API and payload format.
 * Maven excludes the dependency's replacement class from the shaded JAR.
 */
public abstract class GlobalMessageProxyHandler {
    private final ArrayList<GlobalMessageListener> globalMessageListeners = new ArrayList<>();

    public GlobalMessageProxyHandler() {
    }

    public void addListener(GlobalMessageListener listener) {
        globalMessageListeners.add(listener);
    }

    public void onMessage(String subChannel, ArrayList<String> message) {
        for (GlobalMessageListener listener : globalMessageListeners) {
            if (listener.getSubChannel().equalsIgnoreCase(subChannel)) {
                listener.onReceive(message);
            }
        }
    }

    public abstract void sendMessage(String server, String subChannel, String... messageData);
}
