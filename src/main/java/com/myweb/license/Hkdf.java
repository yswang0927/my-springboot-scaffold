package com.myweb.license;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.Arrays;

/** HKDF-SHA256（RFC 5869 extract-then-expand），纯 JDK 实现。 */
public final class Hkdf {

    private static final int HASH_LEN = 32;

    private Hkdf() {}

    public static byte[] derive(byte[] ikm, byte[] salt, byte[] info, int length) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            // extract
            byte[] saltBytes = salt.length == 0 ? new byte[HASH_LEN] : salt;
            mac.init(new SecretKeySpec(saltBytes, "HmacSHA256"));
            byte[] prk = mac.doFinal(ikm);
            // expand
            int n = (length + HASH_LEN - 1) / HASH_LEN;
            byte[] okm = new byte[length];
            byte[] t = new byte[0];
            int off = 0;
            for (int i = 1; i <= n; i++) {
                mac.init(new SecretKeySpec(prk, "HmacSHA256"));
                mac.update(t);
                mac.update(info);
                mac.update((byte) i);
                t = mac.doFinal();
                int take = Math.min(HASH_LEN, length - off);
                System.arraycopy(t, 0, okm, off, take);
                off += take;
            }
            Arrays.fill(t, (byte) 0);
            return okm;
        } catch (Exception e) {
            throw new LicenseException("HKDF failed", e);
        }
    }
}
