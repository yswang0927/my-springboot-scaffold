package com.myweb.license;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 字符串混淆工具（复刻 Aspose 两套方案，用于编译期常量遮蔽）：
 * <ul>
 *   <li>scheme A：相邻字节对调 + UTF-8 解码（Aspose zzXqB.zzZg7）</li>
 *   <li>scheme B：逐字符 + XOR 滚动密钥 + mod-97 校验和（Aspose zzYkf/zzWZc.zzXGb）</li>
 * </ul>
 * 生成端把明文编成 int[] 字面量写进源码，运行端解码——反编译产物里看不到明文字符串。
 * 注意：这只是提高静态审计成本的手段，不是密码学保护；真正的密钥保护靠私钥永不进客户端。
 */
public final class StringObfuscator {

    private StringObfuscator() {}

    // ---------- scheme A: 相邻字节对调 ----------

    public static byte[] swapEncode(String plain) {
        return swap(plain.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] swap(byte[] b) {
        for (int i = 0; i + 1 < b.length; i += 2) {
            byte t = b[i]; b[i] = b[i + 1]; b[i + 1] = t;
        }
        return b;
    }

    public static String swapDecode(byte[] encoded) {
        return new String(swap(encoded.clone()), StandardCharsets.UTF_8); // 对调是自逆运算
    }

    /** 生成源码可用的 int[] 字面量，如 {105,108,...} */
    public static String swapToIntArrayLiteral(String plain) {
        return braces(Arrays.toString(swapEncode(plain)));
    }

    private static String braces(String arrayToString) {
        return arrayToString.replace('[', '{').replace(']', '}');
    }

    // ---------- scheme B: XOR 滚动密钥 + 校验和 ----------

    public static int[] xorEncode(String plain, String key) {
        byte[] k = key.getBytes(StandardCharsets.UTF_8);
        int[] out = new int[plain.length() + 2];
        int sum = 0;
        for (int i = 0; i < plain.length(); i++) {
            int c = plain.charAt(i);
            out[i] = (c + k[i % k.length]) ^ ((key.charAt(i % key.length()) * 31) & 0xFF);
            sum = (sum + out[i]) % 97;
        }
        out[plain.length()] = sum;
        out[plain.length() + 1] = plain.length();
        return out;
    }

    public static String xorDecode(int[] v, String key) {
        if (v.length < 2) throw new LicenseException("encoded string corrupt");
        int len = v[v.length - 1];
        int sum = 0;
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            int c = (v[i] ^ ((key.charAt(i % key.length()) * 31) & 0xFF)) - key.charAt(i % key.length());
            sb.append((char) c);
            sum = (sum + v[i]) % 97;
        }
        if (sum != v[len]) throw new LicenseException("encoded string checksum failed");
        return sb.toString();
    }

    public static String xorToIntArrayLiteral(String plain, String key) {
        return braces(Arrays.toString(xorEncode(plain, key)));
    }
}
