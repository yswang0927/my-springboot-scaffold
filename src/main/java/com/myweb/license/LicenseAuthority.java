package com.myweb.license;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 签发端 CLI（私钥只存在于这里——发布产品时绝不带上）。
 *
 * 用法：
 *   keygen    --out-prefix keys                生成 mode=1 的 RSA-3072 密钥对（PKCS#8/X.509 PEM）
 *   issue     --private keys/private.pem \
 *             --product "MyApp for Java" --serial <uuid> --licensee "Acme Co" \
 *             [--expiry 2027-10-13 | 缺省 N/A] [--subexpiry ...] [--features "*" 或 "1,2,21"] \
 *             --out license.lic               签发许可证
 *   blacklist --private keys/private.pem --serials s1,s2 --out blacklist.txt   签发吊销清单
 *   verify    --public keys/public.pem --license license.lic                   自校验
 */
public final class LicenseAuthority {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) { usage(); System.exit(2); }
        Map<String, String> opts = parseArgs(args);
        String cmd = args[0];
        PrintStream out = System.out;
        switch (cmd) {
            case "keygen" -> {
                String prefix = opts.getOrDefault("out-prefix", "keys");
                KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
                g.initialize(3072);
                KeyPair kp = g.generateKeyPair();
                Path dir = Path.of(prefix);
                Files.createDirectories(dir);
                Files.writeString(dir.resolve("private.pem"),
                        LicenseFileFormat.toPem("PRIVATE KEY", kp.getPrivate().getEncoded()),
                        StandardCharsets.UTF_8);
                Files.writeString(dir.resolve("public.pem"),
                        LicenseFileFormat.toPem("PUBLIC KEY", kp.getPublic().getEncoded()),
                        StandardCharsets.UTF_8);
                out.println("keygen ok: mode=1 keypair -> " + dir + "/private.pem , " + dir + "/public.pem");
                out.println("提醒: private.pem 入签发保险库, 不要提交进产品仓库;");
                out.println("      public.pem 嵌入产品(建议经 StringObfuscator 遮蔽后编译进常量)。");
            }
            case "issue" -> {
                PrivateKeyHolder pk = new PrivateKeyHolder(opts);
                LicensePayload payload = new LicensePayload(
                        Integer.parseInt(opts.getOrDefault("mode", "1")),
                        require(opts, "product"),
                        opts.getOrDefault("serial", UUID.randomUUID().toString()),
                        require(opts, "licensee"),
                        LocalDate.now(),
                        parseDateOr(opts, "expiry"),
                        parseDateOr(opts, "subexpiry"),
                        parseFeatures(opts.getOrDefault("features", LicensePayload.FULL)));
                String xml = LicenseFileFormat.issue(payload, pk.key);
                Path o = Path.of(require(opts, "out"));
                Files.writeString(o, xml, StandardCharsets.UTF_8);
                out.println("issued: " + o + "  serial=" + payload.serial()
                        + "  expiry=" + payload.canonical().lines().filter(l -> l.startsWith("expiry")).findFirst());
            }
            case "blacklist" -> {
                PrivateKeyHolder pk = new PrivateKeyHolder(opts);
                List<String> serials = Arrays.asList(require(opts, "serials").split(","));
                Files.writeString(Path.of(require(opts, "out")),
                        BlackList.issue(serials, pk.key), StandardCharsets.UTF_8);
                out.println("blacklist issued with " + serials.size() + " serials");
            }
            case "verify" -> {
                PublicKey pub = LicenseFileFormat.loadPublicKey(
                        Files.readString(Path.of(require(opts, "public")), StandardCharsets.UTF_8));
                int mode = Integer.parseInt(opts.getOrDefault("mode", "1"));
                LicensePayload p = LicenseFileFormat.loadAndVerify(
                        Path.of(require(opts, "license")), mode, pub);
                out.println("signature OK. payload:\n" + p.canonical());
                if (p.expiredAt(LocalDate.now())) out.println("WARNING: license expired");
            }
            default -> { usage(); System.exit(2); }
        }
    }

    private static final class PrivateKeyHolder {
        final PrivateKey key;
        PrivateKeyHolder(Map<String, String> opts) throws Exception {
            key = LicenseFileFormat.loadPrivateKey(
                    Files.readString(Path.of(require(opts, "private")), StandardCharsets.UTF_8));
        }
    }

    private static Set<String> parseFeatures(String v) {
        TreeSet<String> s = new TreeSet<>(Arrays.asList(v.split(",", -1)));
        if (!s.contains(LicensePayload.FULL))
            for (String f : s)
                if (!f.matches("[0-9]+")) throw new LicenseException("feature ids must be numeric or '*': " + f);
        return s;
    }

    private static LocalDate parseDateOr(Map<String, String> opts, String name) {
        String v = opts.get(name);
        return v == null || "N/A".equals(v) ? null : LocalDate.parse(v);
    }

    private static String require(Map<String, String> opts, String name) {
        String v = opts.get(name);
        if (v == null) throw new LicenseException("missing --" + name);
        return v;
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> m = new HashMap<>();
        List<String> rest = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--") && i + 1 < args.length) {
                m.put(args[i].substring(2), args[++i]);
            } else rest.add(args[i]);
        }
        // 允许 --features a b c 形式未支持；仅 --key value
        for (String r : rest) {
            int eq = r.indexOf('=');
            if (eq > 0) m.putIfAbsent(r.substring(0, eq), r.substring(eq + 1));
        }
        return m;
    }

    private static void usage() {
        System.err.println(java.util.Arrays.toString(new String[]{
                "usage: LicenseAuthority keygen|issue|blacklist|verify --<opt> <value> ..."}));
    }
}
