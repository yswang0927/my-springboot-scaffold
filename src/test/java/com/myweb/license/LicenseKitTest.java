package com.myweb.license;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static org.junit.jupiter.api.Assertions.*;

/** license-kit 全链路验证：签发 -> 验签 -> 状态机 -> 功能门控 -> 吊销/过期/篡改负例。 */
@TestMethodOrder(MethodOrderer.MethodName.class)
class LicenseKitTest {

    static KeyPair kp;
    static final String PRODUCT = "MyApp for Java";
    static final Clock NOW = Clock.fixed(Instant.parse("2026-10-13T00:00:00Z"), ZoneOffset.UTC);

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        kp = g.generateKeyPair();
    }

    static LicenseManager manager(BlackList bl) {
        LicenseManager m = new LicenseManager(List.of(PRODUCT), NOW);
        m.registerPublicKey(1, kp.getPublic());
        if (bl != null) m.setBlackList(bl);
        return m;
    }

    static String issueLicense(String serial, Set<String> features, LocalDate expiry) {
        LicensePayload p = new LicensePayload(1, PRODUCT, serial, "Acme Co",
                LocalDate.now(NOW), expiry, null, features);
        String license = LicenseFileFormat.issue(p, kp.getPrivate());
        System.out.println(">> License: "+ license);
        return license;
    }

    static void load(LicenseManager m, String xml) {
        m.setLicense(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void t1_fullLicense_roundtrip() {
        LicenseManager m = manager(null);
        assertEquals(LicenseManager.State.EVALUATION, m.getState());
        load(m, issueLicense("SN-FULL-1", Set.of(LicensePayload.FULL), null));
        assertEquals(LicenseManager.State.LICENSED, m.getState());
        assertTrue(m.isFeatureEnabled("21"), "LICENSED 全量放行");
        assertTrue(m.isLicensed());
    }

    @Test
    void t2_tamperedLicense_signatureRejected() {
        LicenseManager m = manager(null);
        String xml = issueLicense("SN-TAMPER", Set.of(LicensePayload.FULL), null)
                .replace("SN-TAMPER", "SN-OTHER!");
        LicenseException ex = assertThrows(LicenseException.class, () -> load(m, xml));
        assertTrue(ex.getMessage().contains("Invalid license signature"), ex.getMessage());
        assertEquals(LicenseManager.State.EVALUATION, m.getState(), "被拒后不得留状态");
    }

    @Test
    void t3_expiredLicense_rejected() {
        LicenseManager m = manager(null);
        String xml = issueLicense("SN-EXP", Set.of(LicensePayload.FULL), LocalDate.of(2025, 1, 1));
        LicenseException ex = assertThrows(LicenseException.class, () -> load(m, xml));
        assertTrue(ex.getMessage().toLowerCase().contains("expired"), ex.getMessage());
    }

    @Test
    void t4_revokedSerial_rejected() {
        String serial = "SN-BAD";
        BlackList bl = BlackList.load(BlackList.issue(List.of(serial, "SN-X"), kp.getPrivate()), kp.getPublic());
        assertTrue(bl.contains(serial));
        LicenseManager m = manager(bl);
        LicenseException ex = assertThrows(LicenseException.class,
                () -> load(m, issueLicense(serial, Set.of(LicensePayload.FULL), null)));
        assertTrue(ex.getMessage().contains("disabled"), ex.getMessage());
    }

    @Test
    void t5_featureLimited_partialGate() {
        LicenseManager m = manager(null);
        load(m, issueLicense("SN-PART", new TreeSet<>(Set.of("1", "2", "21")), null));
        assertEquals(LicenseManager.State.FEATURE_LIMITED, m.getState());
        assertTrue(m.isFeatureEnabled("21"));
        assertFalse(m.isFeatureEnabled("999"), "未授予功能位应拦截");
    }

    @Test
    void t6_unloadSemantics() {
        LicenseManager m = manager(null);
        load(m, issueLicense("SN-UNLOAD", Set.of(LicensePayload.FULL), null));
        assertEquals(LicenseManager.State.LICENSED, m.getState());
        m.setLicense(""); // Aspose 同款隐式卸载语义
        assertEquals(LicenseManager.State.EVALUATION, m.getState());
        assertFalse(m.isFeatureEnabled("1"));
    }

    @Test
    void t7_unknownKeyMode_rejected() {
        LicenseManager m = manager(null);
        String xml = issueLicense("SN-MODE", Set.of(LicensePayload.FULL), null)
                .replace("<Signature mode=\"1\"", "<Signature mode=\"2\"");
        assertThrows(LicenseException.class, () -> load(m, xml));
    }

    @Test
    void t8_productMismatch_rejected() {
        LicenseManager m = manager(null);
        LicensePayload p = new LicensePayload(1, "OtherProduct", "SN-PROD", "Acme",
                LocalDate.now(NOW), null, null, Set.of(LicensePayload.FULL));
        String xml = LicenseFileFormat.issue(p, kp.getPrivate());
        LicenseException ex = assertThrows(LicenseException.class, () -> load(m, xml));
        assertTrue(ex.getMessage().contains("product"), ex.getMessage());
    }

    @Test
    void t9_malformedXml_rejected() {
        LicenseManager m = manager(null);
        assertThrows(LicenseException.class, () -> load(m, "<not-license/>"));
        // DOCTYPE/外部实体攻击面必须关死
        String xxe = "<?xml version=\"1.0\"?><!License [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<License><Data>&x;</Data><Signature mode=\"1\">AAAA</Signature></License>";
        assertThrows(LicenseException.class, () -> load(m, xxe));
    }

    @Test
    void t10_stringObfuscation_roundtrip() {
        // 与 Aspose 实测向量一致：相邻字节对调方案
        assertEquals("licenseState",
                StringObfuscator.swapDecode(new byte[]{105, 108, 101, 99, 115, 110, 83, 101, 97, 116, 101, 116}));
        assertEquals("{105, 108, 101, 99, 115, 110, 83, 101, 97, 116, 101, 116}",
                StringObfuscator.swapToIntArrayLiteral("licenseState"));
        // XOR + 校验和方案
        String key = "myapp.key";
        String secret = "https://license.myapp.com/pubkey";
        int[] enc = StringObfuscator.xorEncode(secret, key);
        assertFalse(java.util.Arrays.toString(enc).contains("license"), "编码产物不应含明文片段");
        assertEquals(secret, StringObfuscator.xorDecode(enc, key));
        // 篡改校验位 -> 解码失败
        int[] broken = enc.clone();
        broken[0] ^= 0x55;
        assertThrows(LicenseException.class, () -> StringObfuscator.xorDecode(broken, key));
    }

    @Test
    void t11_blacklistTamper_rejected() {
        String good = BlackList.issue(List.of("SN-A"), kp.getPrivate());
        String tampered = good.replace("SN-A", "SN-B");
        assertThrows(LicenseException.class, () -> BlackList.load(tampered, kp.getPublic()));
    }

    @Test
    void t12_enforcementPattern_watermark() {
        // 业务侧 enforcement 模式演示：保存/导出点查询门控，未授权注入水印文本
        LicenseManager m = manager(null);
        String body = "季度报告正文";
        assertEquals("[Evaluation Only] " + body, renderWithWatermark(m, "export", body));
        load(m, issueLicense("SN-WM", Set.of(LicensePayload.FULL), null));
        assertEquals(body, renderWithWatermark(m, "export", body), "LICENSED 后水印消失");
    }

    /** 产品保存/导出入口的水印注入点示例 */
    static String renderWithWatermark(LicenseManager m, String feature, String body) {
        return m.isFeatureEnabled(feature) ? body : "[Evaluation Only] " + body;
    }

    // ================= 密钥绑定门控 + 交叉验证 =================

    @Test
    void t13_hkdf_matchesJdkMac() throws Exception {
        // L=32 时 HKDF expand 首块 = HMAC(PRK, info||0x01)，与 JDK Mac 独立复算一致
        byte[] ikm = "signature-like-bytes".getBytes(StandardCharsets.UTF_8);
        byte[] salt = "export|SN-1".getBytes(StandardCharsets.UTF_8);
        byte[] info = "licensekit/feature/v1".getBytes(StandardCharsets.UTF_8);
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);
        mac.init(new javax.crypto.spec.SecretKeySpec(prk, "HmacSHA256"));
        mac.update(info);
        mac.update((byte) 1);
        byte[] expected = mac.doFinal();
        assertArrayEquals(expected, Hkdf.derive(ikm, salt, info, 32));
        // 长度扩张 + salt 敏感性
        assertEquals(64, Hkdf.derive(ikm, salt, info, 64).length);
        assertFalse(java.util.Arrays.equals(expected,
                Hkdf.derive(ikm, "other|SN-1".getBytes(StandardCharsets.UTF_8), info, 32)));
    }

    @Test
    void t14_featureSecret_gating() {
        LicenseManager m = manager(null);
        assertThrows(LicenseException.class, () -> m.signatureDerivedSecret("export"),
                "EVALUATION 态不得派生任何密钥");
        load(m, issueLicense("SN-SEC", Set.of(LicensePayload.FULL), null));
        byte[] s1 = m.signatureDerivedSecret("export");
        byte[] s2 = m.signatureDerivedSecret("export");
        assertEquals(32, s1.length);
        assertArrayEquals(s1, s2, "同参数派生必须确定性一致");
        assertFalse(java.util.Arrays.equals(s1, m.signatureDerivedSecret("convert")),
                "不同功能位密钥必须隔离");

        // 部分授权：仅授予 "1"，功能 "2" 不可派生
        LicenseManager m2 = manager(null);
        load(m2, issueLicense("SN-SEC2", new TreeSet<>(Set.of("1")), null));
        assertNotNull(m2.signatureDerivedSecret("1"));
        assertThrows(LicenseException.class, () -> m2.signatureDerivedSecret("2"));
    }

    @Test
    void t15_crossValidator_detectsPatchedBooleanGate() {
        // 正常态：两站一致
        LicenseManager m = manager(null);
        load(m, issueLicense("SN-XV", Set.of(LicensePayload.FULL), null));
        assertEquals(CrossValidator.Verdict.ENABLED, new CrossValidator(m).probe("export"));

        LicenseManager free = manager(null);
        assertEquals(CrossValidator.Verdict.DISABLED, new CrossValidator(free).probe("export"));

        // 模拟字节码 patch：站 A（布尔门控）被改成恒真，站 B（密钥派生）保持真实行为
        CrossValidator patched = new CrossValidator(f -> true, free::signatureDerivedSecret);
        assertEquals(CrossValidator.Verdict.TAMPER_SUSPECTED, patched.probe("export"),
                "恒真 patch 必须被两站不一致暴露");
        // 反向 patch（恒假，针对付费用户的破坏/降级攻击）同样暴露
        CrossValidator sabotaged = new CrossValidator(f -> false, m::signatureDerivedSecret);
        assertEquals(CrossValidator.Verdict.TAMPER_SUSPECTED, sabotaged.probe("export"));
    }

    @Test
    void t16_assetCipher_binding_booleanPatchUseless() {
        // 厂商流程：签发许可证时（同一确定性 RSA 签名）派生密钥并封存受限资产
        String serial = "SN-ASSET";
        LicensePayload p = new LicensePayload(1, PRODUCT, serial, "Acme",
                LocalDate.now(NOW), null, null, Set.of(LicensePayload.FULL));
        byte[] sig = LicenseFileFormat.sign(p.canonical().getBytes(StandardCharsets.UTF_8), kp.getPrivate());
        byte[] vendorSecret = Hkdf.derive(sig,
                ("export|" + serial).getBytes(StandardCharsets.UTF_8),
                "licensekit/feature/v1".getBytes(StandardCharsets.UTF_8), 32);
        byte[] asset = "高质量导出模板数据".getBytes(StandardCharsets.UTF_8);
        byte[] sealed = LicensedAssetCipher.seal(vendorSecret, asset);

        // 正版客户端：许可证验签通过 -> 派生出同一密钥 -> 资产可用
        LicenseManager m = manager(null);
        String xml = LicenseFileFormat.issue(p, kp.getPrivate());
        m.setLicense(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        assertArrayEquals(asset, LicensedAssetCipher.open(m.signatureDerivedSecret("export"), sealed));

        // 破解者 patch 布尔门控恒真：没有合法许可证签名 -> 无密钥 -> 资产解不开（GCM tag 拒绝）
        LicenseManager cracked = manager(null);
        CrossValidator fakeGate = new CrossValidator(f -> true, cracked::signatureDerivedSecret);
        assertEquals(CrossValidator.Verdict.TAMPER_SUSPECTED, fakeGate.probe("export"));
        assertThrows(LicenseException.class, () -> cracked.signatureDerivedSecret("export"));
        // 用错误密钥强行解密 -> LicenseException（产出损坏而非成品）
        byte[] wrong = Hkdf.derive("forged".getBytes(StandardCharsets.UTF_8),
                ("export|" + serial).getBytes(StandardCharsets.UTF_8),
                "licensekit/feature/v1".getBytes(StandardCharsets.UTF_8), 32);
        assertThrows(LicenseException.class, () -> LicensedAssetCipher.open(wrong, sealed));
    }

    @Test
    void t17_integrityProbes_runWithoutCrashing() {
        // 探针在无 jar/无 agent 的正常测试环境必须不误报崩溃
        CrossValidator.Verdict v = new CrossValidator(manager(null))
                .probeHardened("export", LicenseManager.class, null);
        assertEquals(CrossValidator.Verdict.DISABLED, v);
        String digest = CrossValidator.classDigest(LicenseManager.class);
        assertNotNull(digest, "classes 目录形态应可读取自身字节码摘要");
        assertEquals(digest, CrossValidator.classDigest(LicenseManager.class), "摘要确定性");
        assertTrue(CrossValidator.integrityOk(LicenseManager.class, digest));
        assertFalse(CrossValidator.integrityOk(LicenseManager.class,
                "0000000000000000000000000000000000000000000000000000000000000000"));
    }
}
