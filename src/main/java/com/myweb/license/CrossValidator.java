package com.myweb.license;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.function.Function;
import java.util.jar.JarFile;

/**
 * 交叉验证骨架：同一功能授权判定走两条独立路径，结果互校。
 * <ul>
 *   <li>站 A = 布尔门控（isFeatureEnabled）——最容易被字节码 patch 的点</li>
 *   <li>站 B = 密钥派生（signatureDerivedSecret）——patch 布尔值不影响此路径；
 *       攻击者要让两站同时"一致地错"，必须连 HKDF/验签链一起重建</li>
 * </ul>
 * 判定策略（关键）：不一致时不抛错、不崩，返回 SUSPECT 并由业务侧选择
 * 静默降级（功能输出劣化/延迟/悄悄上报）——让攻击者难以定位是哪一步被发现。
 */
public final class CrossValidator {

    public enum Verdict { ENABLED, DISABLED, TAMPER_SUSPECTED }

    private final Function<String, Boolean> gateSite;
    private final Function<String, byte[]> secretSite;

    /** 生产接线：两站都来自同一 LicenseManager 的不同公开面。 */
    public CrossValidator(LicenseManager manager) {
        this(manager::isFeatureEnabled, manager::signatureDerivedSecret);
    }

    /** 可注入两站探测函数（测试/多引擎场景）。 */
    public CrossValidator(Function<String, Boolean> gateSite, Function<String, byte[]> secretSite) {
        this.gateSite = gateSite;
        this.secretSite = secretSite;
    }

    public Verdict probe(String featureId) {
        boolean a;
        try {
            a = Boolean.TRUE.equals(gateSite.apply(featureId));
        } catch (Throwable t) {
            a = false;
        }
        boolean b;
        try {
            byte[] s = secretSite.apply(featureId);
            b = s != null && s.length == 32;
        } catch (Throwable t) {
            b = false;
        }
        if (a == b) return a ? Verdict.ENABLED : Verdict.DISABLED;
        return Verdict.TAMPER_SUSPECTED;
    }

    // ---------- 环境可疑度探针（提高成本层，非决定层） ----------

    /** attach/javaagent 痕迹探测：JVM 启动参数或自带 agent 列表。 */
    public static boolean agentSuspected() {
        try {
            RuntimeMXBean rb = ManagementFactory.getRuntimeMXBean();
            String joined = (rb.getName() + " " + String.join(" ", rb.getInputArguments()))
                    .toLowerCase(Locale.ROOT);
            return joined.contains("javaagent") || joined.contains("agentlib")
                    || joined.contains("attach") || joined.contains("jdwp");
        } catch (Exception e) {
            return false;
        }
    }

    /** 关键类自身字节码摘要（jar 内条目或 classes 目录均可读取）。 */
    public static String classDigest(Class<?> c) {
        try {
            var cs = c.getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) return null;
            byte[] bytes;
            java.net.URL url = cs.getLocation();
            String path = url.getPath();
            String entry = c.getName().replace('.', '/') + ".class";
            if (path.endsWith(".jar")) {
                try (JarFile jar = new JarFile(new java.io.File(url.toURI()))) {
                    bytes = jar.getInputStream(jar.getEntry(entry)).readAllBytes();
                }
            } else {
                bytes = Files.readAllBytes(java.nio.file.Path.of(url.toURI()).resolve(entry));
            }
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 完整性基线校验：expectedDigest 应在构建期生成并遮蔽嵌入（StringObfuscator），
     * 未配置（null/空）时不启用。命中不一致 = 类被重编译替换。
     */
    public static boolean integrityOk(Class<?> c, String expectedDigest) {
        if (expectedDigest == null || expectedDigest.isEmpty()) return true;
        String actual = classDigest(c);
        return actual != null && actual.equalsIgnoreCase(expectedDigest);
    }

    /** 综合判定：业务侧示例策略——可疑即降级，绝不因探针失败而崩溃暴露探针存在。 */
    public Verdict probeHardened(String featureId, Class<?> gateClass, String expectedDigest) {
        Verdict v = probe(featureId);
        if (v == Verdict.TAMPER_SUSPECTED) return v;
        if (agentSuspected() || !integrityOk(gateClass, expectedDigest))
            return v == Verdict.ENABLED ? Verdict.TAMPER_SUSPECTED : v;
        return v;
    }
}
