package com.myweb.license;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * 授权资产加解密演示：产品内的受限资源（模板、字体子集、核心模型、无水印渲染管线数据等）
 * 用许可证签名派生的密钥加密分发。运行时只有持合法签名许可的用户能解出可用内容——
 * 「isFeatureEnabled 返回 true」不再等于功能可用，因为缺的是密钥位，不是布尔位。
 */
public final class LicensedAssetCipher {

    private LicensedAssetCipher() {}

    /** 厂商侧：用与客户端派生规则一致的密钥加密资产。 */
    public static byte[] seal(byte[] featureSecret, byte[] plain) {
        return gcm(Cipher.ENCRYPT_MODE, featureSecret, plain);
    }

    /** 客户侧：密钥不正确（未授权/信封被篡改/伪造许可）时 GCM tag 校验必失败。 */
    public static byte[] open(byte[] featureSecret, byte[] sealed) {
        return gcm(Cipher.DECRYPT_MODE, featureSecret, sealed);
    }

    private static byte[] gcm(int mode, byte[] featureSecret, byte[] data) {
        try {
            byte[] key = MessageDigest.getInstance("SHA-256")
                    .digest(concat("licensekit/asset/v1".getBytes(StandardCharsets.UTF_8), featureSecret));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            if (mode == Cipher.ENCRYPT_MODE) {
                byte[] iv = new byte[12];
                new SecureRandom().nextBytes(iv);
                c.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
                byte[] ct = c.doFinal(data);
                return concat(iv, ct);
            }
            byte[] iv = new byte[12];
            System.arraycopy(data, 0, iv, 0, 12);
            c.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            return c.doFinal(data, 12, data.length - 12);
        } catch (javax.crypto.AEADBadTagException e) {
            throw new LicenseException("Asset not readable with this license (wrong or missing key).", e);
        } catch (Exception e) {
            throw new LicenseException("asset cipher failed", e);
        }
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int off = 0;
        for (byte[] p : parts) { System.arraycopy(p, 0, out, off, p.length); off += p.length; }
        return out;
    }
}
