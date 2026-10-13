package com.myweb.license;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行时许可证引擎（复刻 Aspose zzoH 的公开面）：
 * <ul>
 *   <li>setLicense(...)：加载并全链路校验（验签→模式→白名单→黑名单→日期），失败即抛；空串 = 隐式卸载</li>
 *   <li>getState()：三态状态机 EVALUATION / LICENSED / FEATURE_LIMITED</li>
 *   <li>isFeatureEnabled(id)：功能位门控，保存/导出等 enforcement 点查询此方法</li>
 * </ul>
 * 与 Aspose 的差异（有意为之）：每次状态判定都重走完整校验链（含验签），
 * 内存里不存在可被反射注入后直接生效的「裸状态」。
 */
public final class LicenseManager {

    public enum State { EVALUATION, LICENSED, FEATURE_LIMITED }

    /** mode -> 验签公钥（支持密钥轮换，对应 Aspose 双键设计） */
    private final Map<Integer, PublicKey> keyRegistry = new ConcurrentHashMap<>();
    /** 允许的产品名白名单 */
    private final List<String> productWhitelist;
    /** 可空：离线吊销清单 */
    private volatile BlackList blackList;
    private volatile Acceptance accepted;
    private final Clock clock;

    /** 落账物 = 载荷 + 原始信封；状态判定需对信封复验。 */
    private record Acceptance(LicensePayload payload, LicenseFileFormat.Envelope envelope) {}

    public LicenseManager(List<String> productWhitelist, Clock clock) {
        this.productWhitelist = List.copyOf(productWhitelist);
        this.clock = clock;
    }

    public void registerPublicKey(int mode, PublicKey key) {
        keyRegistry.put(mode, key);
    }

    public void setBlackList(BlackList list) {
        this.blackList = list;
    }

    /** 加载 .lic 文件路径；"" = 卸载（与 Aspose 行为一致）。 */
    public void setLicense(String pathOrName) {
        if (pathOrName == null || pathOrName.isEmpty()) {
            accepted = null;
            return;
        }
        Path p = Path.of(pathOrName);
        try {
            setLicense(Files.newInputStream(p));
        } catch (java.io.IOException e) {
            throw new LicenseException("license file not found: " + pathOrName, e);
        }
    }

    public void setLicense(InputStream in) {
        try {
            String xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            LicenseFileFormat.Envelope env = LicenseFileFormat.parseXml(xml);
            LicensePayload payload = verifyEnvelope(env);

            accepted = new Acceptance(payload, env); // 全链通过才落账（含信封，供状态判定复验）
        } catch (java.io.IOException e) {
            throw new LicenseException("license stream not readable", e);
        }
    }

    /** 完整校验链：公钥存在 -> 验签 -> 规范串一致 -> 白名单 -> 黑名单 -> 日期。 */
    private LicensePayload verifyEnvelope(LicenseFileFormat.Envelope env) {
        PublicKey key = keyRegistry.get(env.signatureMode());
        if (key == null)
            throw new LicenseException("Unsupported license key mode: " + env.signatureMode());
        if (!LicenseFileFormat.verify(
                env.canonicalData().getBytes(StandardCharsets.UTF_8), env.signature(), key))
            throw new LicenseException(
                    "Invalid license signature. Please make sure the license file was not modified.");
        LicensePayload payload = LicensePayload.parse(env.canonicalData());
        if (payload.mode() != env.signatureMode())
            throw new LicenseException("payload mode disagrees with envelope");
        BlackList bl = blackList;
        if (bl != null && bl.contains(payload.serial()))
            throw new LicenseException("The license is disabled, please contact the vendor to obtain a new license.");
        if (!productWhitelist.contains(payload.product()))
            throw new LicenseException("License product does not match this component.");
        if (payload.expiredAt(LocalDate.now(clock)))
            throw new LicenseException("The license has expired.");
        return payload;
    }

    /** 当前状态：每次调用都重走验签 + 日期 + 黑名单复核，内存裸状态不可信。 */
    public State getState() {
        return computeState().state();
    }

    public boolean isFeatureEnabled(String featureId) {
        StateResult r = computeState();
        return switch (r.state()) {
            case EVALUATION -> false;
            case LICENSED -> true;
            case FEATURE_LIMITED -> r.payload().features().contains(featureId);
        };
    }

    /** 未授权时给水印/限制提示等 enforcement 点用的快捷判断。 */
    public boolean isLicensed() {
        State s = getState();
        return s == State.LICENSED || s == State.FEATURE_LIMITED;
    }

    /**
     * 功能密钥派生：IKM = 许可证 RSA 签名字节（不可伪造的授权秘密），
     * salt = feature|serial，info 固定。只有校验链全通过才可派生。
     * 与布尔门控的本质区别：patch 方法返回值得不到正确密钥，
     * 用返回值驱动的功能（资产解密等）会直接产出损坏结果。
     */
    public byte[] signatureDerivedSecret(String featureId) {
        Acceptance a = accepted;
        if (a == null) throw new LicenseException("Feature not licensed: " + featureId);
        StateResult r = computeState();
        if (r.state() == State.EVALUATION)
            throw new LicenseException("Feature not licensed: " + featureId);
        if (r.state() == State.FEATURE_LIMITED && !r.payload().features().contains(featureId))
            throw new LicenseException("Feature not licensed: " + featureId);
        byte[] secret = Hkdf.derive(
                a.envelope().signature(),
                (featureId + "|" + a.payload().serial()).getBytes(StandardCharsets.UTF_8),
                "licensekit/feature/v1".getBytes(StandardCharsets.UTF_8),
                32);
        // 消费方拿到密钥的同时应做自我校验（防被反射篡改信封字节后派生错钥）：
        // 密钥正确性由派生自真实 RSA 签名的 HKDF 链保证——信封字节被篡改 ->
        // computeState 复验失败 -> 这里已经抛异常。
        return secret;
    }

    private record StateResult(State state, LicensePayload payload) {}

    private StateResult computeState() {
        Acceptance a = accepted;
        if (a == null) return new StateResult(State.EVALUATION, null);
        PublicKey key = keyRegistry.get(a.payload().mode());
        if (key == null) return new StateResult(State.EVALUATION, null);
        // 复验签名：即便对象图被反射篡改，签名对不上即刻降级
        if (!LicenseFileFormat.verify(
                a.envelope().canonicalData().getBytes(StandardCharsets.UTF_8),
                a.envelope().signature(), key))
            return new StateResult(State.EVALUATION, null);
        BlackList bl = blackList;
        if ((bl != null && bl.contains(a.payload().serial()))
                || a.payload().expiredAt(LocalDate.now(clock)))
            return new StateResult(State.EVALUATION, null);
        return new StateResult(a.payload().isFull() ? State.LICENSED : State.FEATURE_LIMITED, a.payload());
    }
}
