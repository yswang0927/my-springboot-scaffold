package com.myweb.license;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;
import java.util.List;
import java.util.TreeSet;

/**
 * 离线吊销黑名单（复刻 Aspose 的 BlackList.Real.xml 思路）：
 * 随产品分发一个已签名的序列号清单，命中即拒绝，且清单本身不可篡改。
 * 格式与许可证同款信封：&lt;BlackList&gt;&lt;Data&gt;...&lt;Data&gt;&lt;Signature&gt;base64&lt;/Signature&gt;
 */
public final class BlackList {

    /** 签发端：私钥签名吊销清单。 */
    public static String issue(List<String> serials, PrivateKey key) {
        String canonical = canonical(serials);
        byte[] sig = LicenseFileFormat.sign(canonical.getBytes(StandardCharsets.UTF_8), key);
        return "<BlackList>\n"
                + "  <Data>" + canonical.strip() + "</Data>\n"
                + "  <Signature>" + Base64.getEncoder().encodeToString(sig) + "</Signature>\n"
                + "</BlackList>\n";
    }

    /** 校验端：验签失败 = 清单不可信 = 直接拒绝加载（宁停不错放）。 */
    public static BlackList load(Path file, PublicKey publicKey) {
        try {
            return load(Files.readString(file, StandardCharsets.UTF_8), publicKey);
        } catch (java.io.IOException e) {
            throw new LicenseException("blacklist not readable", e);
        }
    }

    public static BlackList load(String xml, PublicKey publicKey) {
        int dataStart = xml.indexOf("<Data>"), dataEnd = xml.indexOf("</Data>");
        int sigStart = xml.indexOf("<Signature>"), sigEnd = xml.indexOf("</Signature>");
        if (dataStart < 0 || dataEnd < 0 || sigStart < 0 || sigEnd < 0)
            throw new LicenseException("Invalid blacklist format.");
        String canonical = xml.substring(dataStart + 6, dataEnd).strip() + "\n";
        byte[] sig;
        try {
            sig = Base64.getDecoder().decode(xml.substring(sigStart + 11, sigEnd).trim());
        } catch (IllegalArgumentException e) {
            throw new LicenseException("Invalid blacklist format.", e);
        }
        if (!LicenseFileFormat.verify(canonical.getBytes(StandardCharsets.UTF_8), sig, publicKey))
            throw new LicenseException("Invalid blacklist signature.");
        return new BlackList(parseSerials(canonical));
    }

    private final TreeSet<String> serials;

    private BlackList(TreeSet<String> serials) {
        this.serials = serials;
    }

    public boolean contains(String serial) {
        return serials.contains(serial);
    }

    public int size() {
        return serials.size();
    }

    private static String canonical(List<String> serials) {
        StringBuilder sb = new StringBuilder("v1\n");
        for (String s : new TreeSet<>(serials)) sb.append("S=").append(s).append('\n');
        return sb.toString();
    }

    private static TreeSet<String> parseSerials(String canonical) {
        TreeSet<String> out = new TreeSet<>();
        String[] lines = canonical.split("\n", -1);
        if (lines.length == 0 || !"v1".equals(lines[0]))
            throw new LicenseException("Invalid blacklist format.");
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isEmpty()) continue;
            if (!lines[i].startsWith("S=")) throw new LicenseException("Invalid blacklist format.");
            out.add(lines[i].substring(2));
        }
        return out;
    }
}
