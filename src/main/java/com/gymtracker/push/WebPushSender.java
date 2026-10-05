package com.gymtracker.push;

import java.security.GeneralSecurityException;
import java.security.Security;
import nl.martijndwars.webpush.Encoding;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Urgency;
import org.apache.http.HttpResponse;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class WebPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(WebPushSender.class);
    private static final int TTL_SECONDS = 60;

    private final PushService pushService;

    public WebPushSender(@Value("${app.vapid.public-key}") String publicKey,
                         @Value("${app.vapid.private-key}") String privateKey,
                         @Value("${app.vapid.subject}") String subject) {
        if (isBlank(publicKey) || isBlank(privateKey) || isBlank(subject)) {
            throw new IllegalStateException("VAPID_PUBLIC_KEY, VAPID_PRIVATE_KEY and VAPID_SUBJECT must be set");
        }
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        try {
            this.pushService = new PushService(publicKey, privateKey, subject);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Invalid VAPID keys", e);
        }
    }

    @Override
    public SendResult send(PushSubscription subscription, String payloadJson) {
        try {
            Notification notification = Notification.builder()
                    .endpoint(subscription.getEndpoint())
                    .userPublicKey(subscription.getP256dh())
                    .userAuth(subscription.getAuth())
                    .payload(payloadJson)
                    .ttl(TTL_SECONDS)
                    .urgency(Urgency.HIGH)
                    .build();
            HttpResponse response = pushService.send(notification, Encoding.AES128GCM);
            int status = response.getStatusLine().getStatusCode();
            if (status == 404 || status == 410) {
                return SendResult.GONE;
            }
            if (status >= 200 && status < 300) {
                return SendResult.DELIVERED;
            }
            log.warn("Push service answered {} for {}", status, subscription.getEndpoint());
            return SendResult.FAILED;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Sending push notification failed", e);
            return SendResult.FAILED;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
