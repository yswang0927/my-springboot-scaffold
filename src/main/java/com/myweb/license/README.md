# license-kit：产品授权机制使用指南

复刻自 Aspose.Words for Java 的 license 体系（RSA 签名 XML + 三态状态机 + 功能位门控 + 签名黑名单 + 字符串混淆），并针对审计中发现的弱点做了加固（见文末「与 Aspose 的差异」）。

- 纯 JDK 17 实现，零第三方依赖
- 全链路测试：`mvn test`（17 项，含针对「字节码 patch 门控」的攻防用例）

---

## 0. 角色与密钥边界（先读这个）

| 角色 | 持有什么 | 用哪些类 |
|---|---|---|
| **签发库（厂商，离线保管）** | RSA **私钥** | `LicenseAuthority` CLI、`LicenseFileFormat.issue`、`LicensedAssetCipher.seal` |
| **你的产品（分发件）** | RSA **公钥**（遮蔽嵌入） | `LicenseManager`、`CrossValidator`、`LicensedAssetCipher.open` |

铁律：**私钥永远不进产品仓库、不进客户端**。一旦私钥泄漏，整套体系作废（只能换密钥，见 §6 轮换）。

---

## 1. 一次性准备：生成密钥对

```bash
mvn -q package -DskipTests
java -cp target/classes com.example.licensekit.LicenseAuthority keygen --out-prefix /secure/keys
```

产出 `/secure/keys/private.pem`（入保险库/KMS）与 `public.pem`。把 `private.pem` 的拷贝路径记在签发工单里，然后忘掉它——日常签发只用 CLI。

## 2. 产品集成（客户端，共 4 步）

### 2.1 嵌入公钥（务必遮蔽）

不要把 PEM 明文放进资源文件（`strings`/`grep` 一扫即中，还可能被整体替换成攻击者的公钥）。用 `StringObfuscator` 把公钥 Base64 编成 `int[]` 常量编译进代码：

```java
// 构建期生成（跑一次即可，产物贴进源码）：
String b64 = Base64.getEncoder().encodeToString(publicKeyDer);
String literal = StringObfuscator.xorToIntArrayLiteral(b64, "prod.a");  // {83,112,...}

// 产品源码中：
private static final String KEY = "prod.a";
private static final int[] OBFUSCATED_PUB = /* literal */ { ... };

static PublicKey embeddedPublicKey() {
    return LicenseFileFormat.loadPublicKey(StringObfuscator.xorDecode(OBFUSCATED_PUB, KEY));
}
```

进阶（Aspose 同款手法）：把常量拆成 2–3 段，散布在不相干的类里，运行时拼接。

### 2.2 建立应用级 LicenseManager

```java
LicenseManager manager = new LicenseManager(
        List.of("MyApp for Java"),        // 产品名白名单：只有名单内产品的许可证可用
        Clock.systemDefaultZone());
manager.registerPublicKey(1, embeddedPublicKey());   // mode=1，与签发时一致

// 可选：随产品分发签名的吊销黑名单文件（见 §5）
manager.setBlackList(BlackList.load(Path.of("myapp-blacklist.txt"), embeddedPublicKey()));
```

### 2.3 暴露客户侧激活 API

模仿 Aspose 的公开面即可：

```java
public final class License {
    public void setLicense(String path) { manager.setLicense(path); }       // "" = 卸载
    public void setLicense(InputStream in) { manager.setLicense(in); }
}
```

客户代码：`new License().setLicense("myapp.lic");` 失败抛 `LicenseException`（格式非法/验签失败/过期/被吊销/产品不符）。

### 2.4 在 enforcement 点接门控（关键步骤）

**每一个**受限功能的入口（保存、导出、生成等）都要查门控，且推荐走交叉验证而不是裸布尔：

```java
// 普通受限功能（水印类）：
CrossValidator.Verdict v = new CrossValidator(manager).probe("export");
if (v != CrossValidator.Verdict.ENABLED) {
    output = appendWatermark(output);     // DISABLED 与 TAMPER_SUSPECTED 一律降级
}

// 核心付费功能（密钥绑定，推荐）：
byte[] secret = manager.signatureDerivedSecret("export");
Asset asset = LicensedAssetCipher.open(secret, sealedAsset);   // 没有合法许可证必然解不开
```

对应的厂商侧（§3 签发时同步封存资产）：受限资源出厂就用同一规则派生的密钥 `LicensedAssetCipher.seal` 后随产品分发。

## 3. 给客户签发许可证（订单流程）

```bash
java -cp target/classes com.example.licensekit.LicenseAuthority issue \
  --private /secure/keys/private.pem \
  --product "MyApp for Java" \
  --licensee "Acme Co" \
  --serial $(uuidgen) \
  --out acme-myapp.lic
```

常用选项：

| 选项 | 含义 | 缺省 |
|---|---|---|
| `--expiry 2027-10-13` | 授权到期日（永久授权不填 = N/A） | N/A |
| `--subexpiry 2026-12-31` | 订阅期（管版本更新资格） | N/A |
| `--features "*"` | 全量授权 → 客户端 LICENSED 态 | `*` |
| `--features "1,2,21"` | 按功能位授权 → 客户端 FEATURE_LIMITED 态 | — |
| `--mode 1` | 用哪把私钥签（密钥轮换用，见 §6） | 1 |

签发后自校验一遍再发给客户：

```bash
java -cp target/classes com.example.licensekit.LicenseAuthority verify \
  --public /secure/keys/public.pem --license acme-myapp.lic
```

把 `--serial` 登记进你的 CRM——它是后续吊销的唯一凭据。

## 4. 许可证类型与客户端状态机

| 签发的 features | 客户端 `getState()` | 门控行为 |
|---|---|---|
| 未设置许可证 | `EVALUATION` | 全部拒绝（水印/降级） |
| `"*"`（有效未过期） | `LICENSED` | 全部放行 |
| `"1,2,21"` | `FEATURE_LIMITED` | 仅名单内功能位放行 |
| 过期 / 被吊销 / 产品不符 / 篡改 | 加载时抛异常；已加载的在下次判定时自动降级 `EVALUATION` | 全部拒绝 |

## 5. 吊销（许可证外泄/客户退款）

```bash
java -cp target/classes com.example.licensekit.LicenseAuthority blacklist \
  --private /secure/keys/private.pem \
  --serials <serial1>,<serial2> \
  --out myapp-blacklist.txt
```

黑名单文件本身带 RSA 签名，攻击者无法伪造或篡改（`t11` 用例验证）。两种分发方式：

1. **随版本更新**：文件打包进产品发行物（Aspose 方式，离线生效但有滞后）；
2. **在线拉取**：产品启动时从你的服务器取最新版（建议校验 HTTP 签名外的 `updated` 滞后阈值，如超过 30 天未更新则告警）。

## 6. 密钥轮换（公钥疑似暴露/私钥泄漏时）

1. `keygen` 生成新密钥对，新公钥以 **mode=2** 注册：`manager.registerPublicKey(2, newPub)`；
2. 过渡期内 mode=1 的旧公钥保留注册（存量许可证继续可用），新签发的许可证一律 `--mode 2`；
3. 下一个大版本移除 mode=1。

## 7. 构建期完整性基线（配合 CrossValidator.probeHardened）

在自己的构建/发布脚本里调用 `CrossValidator.classDigest(...)` 计算关键类摘要，用 `StringObfuscator` 遮蔽后编译进产品：

```java
String digest = CrossValidator.classDigest(LicenseManager.class);   // 构建期取摘要
// 运行期：
new CrossValidator(manager).probeHardened("export", LicenseManager.class, EXPECTED_DIGEST);
```

`probeHardened` 综合三站：双站一致性 + agent 痕迹 + 类字节码摘要。任一可疑 → `TAMPER_SUSPECTED` → 业务侧**静默降级**（不要抛错崩溃，那会告诉破解者探针在哪）。

## 8. 威胁模型与诚实的边界

这套体系防得住：
- 手工改许可证字段（验签拒绝，`t2`）
- 拿别人公司的许可证用（产品名白名单，`t8`）
- 泄漏/退款客户续用（黑名单，`t4`）
- 到期后继续用（日期语义 + 每次判定复核，`t3`）
- 反射篡改内存状态（状态判定复验签名，`t7/t8` 同款攻击对本 kit 无效）
- 单点 patch `isFeatureEnabled` 恒真（交叉验证暴露 + 资产密钥绑定让布尔位失效，`t15/t16`）

防不住（所有纯客户端方案的共同上限）：
- 把整个加密/派生链逆向搬走的定向破解；
- 运行时内存里资产解密后的成品截取。

因此判断标准很简单：**「去掉水印能不能靠复制粘贴输出完成」的功能，客户端保护只挡君子；「资产/算法本身必须正确才出成品」的功能，才值得投客户端强度保护。真金白银的核心能力放服务端。**

## 附：与 Aspose 的差异（有意改动）

| 项 | Aspose 26.7 | 本 kit |
|---|---|---|
| RSA 实现 | 自写大数运算 | 标准 JCA `SHA256withRSA` |
| 签名对象 | XML 片段文本（依赖 DOM 序列化稳定性） | 固定行序规范串（XML 仅是信封） |
| 状态判定 | 有效记录在案即 LICENSED（内存注入即生效，实测证实） | 每次判定复验签名 + 日期 + 黑名单 |
| XML 解析 | 未见防护 | 关闭 DOCTYPE/外部实体（`t9` 验证） |
| 门控形态 | 裸布尔 + 功能位 | 布尔 + 密钥派生双轨（`signatureDerivedSecret`） |

## 附：文件清单

```
src/main/java/com/example/licensekit/
  LicensePayload.java          数据模型 + 规范序列化
  LicenseFileFormat.java       XML 信封 + 签发/验签 + PEM 编解码
  LicenseManager.java          运行时引擎：三态状态机 + 门控 + 密钥派生
  BlackList.java               签名吊销清单
  CrossValidator.java          双站交叉验证 + agent/完整性探针
  Hkdf.java                    HKDF-SHA256（密钥派生基础件）
  LicensedAssetCipher.java     授权资产 AES-GCM 封存/解封
  StringObfuscator.java        字符串混淆（两套 Aspose 同款方案）
  LicenseAuthority.java        签发端 CLI（keygen/issue/blacklist/verify）
  ProductIntegrationExample.java  产品接线示范
  LicenseException.java
src/test/java/.../LicenseKitTest.java   17 项攻防验证用例
```
