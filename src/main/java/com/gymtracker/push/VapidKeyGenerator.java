package com.gymtracker.push;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

/**
 * Prints a fresh VAPID key pair for Railway's variables:
 * {@code ./mvnw -q compile && java -cp target/classes com.gymtracker.push.VapidKeyGenerator}
 */
public final class VapidKeyGenerator {

    private VapidKeyGenerator() {
    }

    public static void main(String[] args) throws Exception {
        String[] keys = generate();
        System.out.println("VAPID_PUBLIC_KEY=" + keys[0]);
        System.out.println("VAPID_PRIVATE_KEY=" + keys[1]);
    }

    /** Returns {publicKey, privateKey}: base64url without padding; the public key is the uncompressed P-256 point. */
    public static String[] generate() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();
        ECPublicKey publicKey = (ECPublicKey) pair.getPublic();
        ECPrivateKey privateKey = (ECPrivateKey) pair.getPrivate();

        byte[] point = new byte[65];
        point[0] = 0x04;
        System.arraycopy(unsigned32(publicKey.getW().getAffineX()), 0, point, 1, 32);
        System.arraycopy(unsigned32(publicKey.getW().getAffineY()), 0, point, 33, 32);

        Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
        return new String[] {base64.encodeToString(point), base64.encodeToString(unsigned32(privateKey.getS()))};
    }

    private static byte[] unsigned32(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] out = new byte[32];
        int length = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - length, out, 32 - length, length);
        return out;
    }
}
