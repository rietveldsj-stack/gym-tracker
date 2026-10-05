package com.gymtracker.push;

public interface PushSender {

    enum SendResult { DELIVERED, GONE, FAILED }

    SendResult send(PushSubscription subscription, String payloadJson);
}
