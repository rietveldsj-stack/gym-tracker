package com.gymtracker.push;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class FakePushSender implements PushSender {

    private final List<String> sent = new CopyOnWriteArrayList<>();
    private final List<String> payloads = new CopyOnWriteArrayList<>();
    private final Set<String> gone = ConcurrentHashMap.newKeySet();

    @Override
    public SendResult send(PushSubscription subscription, String payloadJson) {
        sent.add(subscription.getEndpoint());
        payloads.add(payloadJson);
        return gone.contains(subscription.getEndpoint()) ? SendResult.GONE : SendResult.DELIVERED;
    }

    public List<String> sent() {
        return List.copyOf(sent);
    }

    public List<String> payloads() {
        return List.copyOf(payloads);
    }

    public void markGone(String endpoint) {
        gone.add(endpoint);
    }

    public void reset() {
        sent.clear();
        payloads.clear();
        gone.clear();
    }
}
