package com.myweb.license;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * 许可证数据模型 + 规范序列化。
 * 签名对象是 canonical() 的 UTF-8 字节（固定行序、固定字段集），
 * 因此验签不依赖 XML 规范化——XML 只是信封。
 */
public record LicensePayload(int mode,
                             String product,
                             String serial,
                             String licensee,
                             LocalDate issued,
                             LocalDate licenceExpiry,      // null = "N/A"（永久授权）
                             LocalDate subscriptionExpiry, // null = "N/A"（无订阅期）
                             Set<String> features) {       // {"*"} = 全量；否则为功能位集合

    /** 全量授权哨兵（对应 Aspose 状态机中 LICENSED 分支的语义） */
    public static final String FULL = "*";

    private static final DateTimeFormatter D = DateTimeFormatter.ISO_LOCAL_DATE;
    /** 值字符集白名单：禁止换行/尖括号/']>' 序列，保证可安全放进 CDATA 且规范串无歧义 */
    private static final Pattern SAFE_VALUE = Pattern.compile("^[A-Za-z0-9 ._:@/+&'()-]+$");
    private static final Pattern FEATURE_TOKEN = Pattern.compile("^(\\*|[0-9]+)$");

    public LicensePayload {
        Objects.requireNonNull(product, "product");
        Objects.requireNonNull(serial, "serial");
        Objects.requireNonNull(licensee, "licensee");
        Objects.requireNonNull(issued, "issued");
        Objects.requireNonNull(features, "features");
        require(features.size() > 0, "features must not be empty");
        for (String f : features)
            require(FEATURE_TOKEN.matcher(f).matches(), "illegal feature token: " + f);
        if (features.contains(FULL))
            require(features.size() == 1, "features '*' cannot be mixed with explicit ids");
    }

    public boolean isFull() {
        return features.contains(FULL);
    }

    public boolean expiredAt(LocalDate today) {
        return (licenceExpiry != null && today.isAfter(licenceExpiry))
                || (subscriptionExpiry != null && today.isAfter(subscriptionExpiry));
    }

    /** 规范化字节串：签发与验签共用，逐字段固定顺序。 */
    public String canonical() {
        return "v1\n"
                + "mode=" + mode + "\n"
                + "product=" + safe(product) + "\n"
                + "serial=" + safe(serial) + "\n"
                + "licensee=" + safe(licensee) + "\n"
                + "issued=" + issued.format(D) + "\n"
                + "expiry=" + (licenceExpiry == null ? "N/A" : licenceExpiry.format(D)) + "\n"
                + "subexpiry=" + (subscriptionExpiry == null ? "N/A" : subscriptionExpiry.format(D)) + "\n"
                + "features=" + String.join(",", new TreeSet<>(features)) + "\n";
    }

    public static LicensePayload parse(String canonical) {
        String[] lines = canonical.split("\n", -1);
        require(lines.length >= 10 && lines[lines.length - 1].isEmpty(), "payload: bad line structure");
        require("v1".equals(lines[0]), "payload: unsupported version");
        int mode = -1;
        String product = null, serial = null, licensee = null;
        LocalDate issued = null, expiry = null, subExpiry = null;
        Set<String> features = null;
        for (int i = 1; i < lines.length - 1; i++) {
            int eq = lines[i].indexOf('=');
            require(eq > 0, "payload: bad line " + i);
            String key = lines[i].substring(0, eq), val = lines[i].substring(eq + 1);
            switch (key) {
                case "mode" -> mode = Integer.parseInt(val);
                case "product" -> product = val;
                case "serial" -> serial = val;
                case "licensee" -> licensee = val;
                case "issued" -> issued = LocalDate.parse(val, D);
                case "expiry" -> expiry = parseDate(val);
                case "subexpiry" -> subExpiry = parseDate(val);
                case "features" -> features = new TreeSet<>(java.util.Arrays.asList(val.split(",", -1)));
                default -> throw new LicenseException("payload: unknown field " + key);
            }
        }
        require(mode >= 0 && product != null && serial != null && licensee != null
                && issued != null && features != null, "payload: missing required field");
        return new LicensePayload(mode, product, serial, licensee, issued, expiry, subExpiry, features);
    }

    private static LocalDate parseDate(String v) {
        return "N/A".equals(v) ? null : LocalDate.parse(v, D);
    }

    private static String safe(String v) {
        require(SAFE_VALUE.matcher(v).matches(), "illegal characters in value: " + v);
        return v;
    }

    private static void require(boolean cond, String message) {
        if (!cond) throw new LicenseException(message);
    }
}
