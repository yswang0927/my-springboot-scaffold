package com.myweb.license;

import java.time.Clock;
import java.util.List;

/**
 * 产品集成示例（把本 kit 复制进你的工程后，按此模式接线）。
 *
 * 1. 签发库（离线保管，绝不进产品仓库）：LicenseAuthority keygen/issue/blacklist
 * 2. 产品内：嵌入公钥（遮蔽常量）-> LicenseManager 三态 -> enforcement 点查门控
 */
public final class ProductIntegrationExample {

    /**
     * 公钥嵌入：不要以 PEM 明文放在产品里（会被 strings/grep 直接找到并整体替换）。
     * 推荐做法：把 X.509 DER 字节用 StringObfuscator 编码成 int[] 常量编译进代码，
     * 且拆成多段散布在 2-3 个不相干类中——Aspose 就是把密钥/产品名/报错全部编码化散布的。
     * 示意（实际生成命令：StringObfuscator.xorToIntArrayLiteral(base64OfDer, KEY)）：
     */
    private static final String KEY = "prod.a";                 // 与解码端一致的滚动密钥
    private static final int[] OBFUSCATED_PUBLIC_KEY_B64 = {    // 占位；由 keygen 产物生成
            /* StringObfuscator.xorToIntArrayLiteral(<base64 of public.pem>, KEY) */};

    static java.security.PublicKey embeddedPublicKey() {
        String b64 = StringObfuscator.xorDecode(OBFUSCATED_PUBLIC_KEY_B64, KEY);
        return LicenseFileFormat.loadPublicKey(b64);
    }

    // ---------- 应用级单例（对应 Aspose 静态引擎） ----------

    private static final LicenseManager MANAGER = new LicenseManager(
            List.of("MyApp for Java"), Clock.systemDefaultZone());

    static {
        MANAGER.registerPublicKey(1, embeddedPublicKey());
        // 可选：随产品分发签名黑名单资源
        // MANAGER.setBlackList(BlackList.load(Path.of("myapp-blacklist.txt"), embeddedPublicKey()));
    }

    /** 客户侧 API：等价 Aspose License().setLicense(path) */
    public static void activate(String licensePath) {
        MANAGER.setLicense(licensePath);
    }

    // ---------- enforcement 点：每个受限功能入口查一次门控 ----------

    public static byte[] exportReport(byte[] document, String feature) {
        // 交叉验证：布尔门控与密钥派生两站互校，不一致按可疑处理（静默降级）
        CrossValidator.Verdict v = new CrossValidator(MANAGER).probe(feature);
        if (v != CrossValidator.Verdict.ENABLED) {
            document = appendWatermarkBanner(document);
        }
        return renderHtml(document);
    }

    /**
     * 强 enforcement 模式（推荐用于核心付费功能）：功能所需的资产
     * （模板/模型/字体等）出厂即用 signatureDerivedSecret 派生的密钥封存，
     * patch 布尔返回值不再等于功能可用——缺的是密钥位，不是标志位。
     */
    public static byte[] renderWithLicensedAsset(String feature, byte[] sealedAsset) {
        byte[] secret = MANAGER.signatureDerivedSecret(feature);
        return LicensedAssetCipher.open(secret, sealedAsset);
    }

    private static byte[] appendWatermarkBanner(byte[] doc) {
        return ("[Evaluation Only. Contact vendor to remove limitations.]" + "\n")
                .concat(new String(doc, java.nio.charset.StandardCharsets.UTF_8))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static byte[] renderHtml(byte[] doc) {
        return ("<html><body>" + new String(doc, java.nio.charset.StandardCharsets.UTF_8)
                + "</body></html>").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private ProductIntegrationExample() {}
}
