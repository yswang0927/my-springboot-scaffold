package com.myweb.license;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * 许可证信封：XML 文本（&lt;Data&gt; + &lt;Signature&gt;）与 RSA 签名的读写。
 * 签名对象是 LicensePayload.canonical() 的 UTF-8 字节，XML 仅作载体，
 * 因此验签不受 XML 格式化差异影响。
 */
public final class LicenseFileFormat {

    public record Envelope(String canonicalData, byte[] signature, int signatureMode) {}

    private LicenseFileFormat() {}

    // ---------- 签发端 ----------

    /** 用私钥对规范串签名，产出许可证 XML。 */
    public static String issue(LicensePayload payload, PrivateKey signingKey) {
        byte[] sig = sign(payload.canonical().getBytes(StandardCharsets.UTF_8), signingKey);
        return toXml(payload.canonical(), sig, payload.mode());
    }

    public static byte[] sign(byte[] data, PrivateKey key) {
        try {
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initSign(key);
            s.update(data);
            return s.sign();
        } catch (Exception e) {
            throw new LicenseException("signing failed", e);
        }
    }

    // ---------- 校验端 ----------

    /** 解析 + 验签一步完成；验不过直接抛，绝不返回半可信对象。 */
    public static LicensePayload loadAndVerify(Path licFile, int expectedMode, PublicKey publicKey) {
        try {
            return loadAndVerify(Files.readString(licFile, StandardCharsets.UTF_8), expectedMode, publicKey);
        } catch (java.io.IOException e) {
            throw new LicenseException("license file not readable: " + licFile, e);
        }
    }

    public static LicensePayload loadAndVerify(String xml, int expectedMode, PublicKey publicKey) {
        Envelope env = parseXml(xml);
        if (env.signatureMode() != expectedMode)
            throw new LicenseException("license signature mode mismatch");
        if (!verify(env.canonicalData().getBytes(StandardCharsets.UTF_8), env.signature(), publicKey))
            throw new LicenseException("Invalid license signature. Please make sure the license file was not modified.");
        LicensePayload payload = LicensePayload.parse(env.canonicalData());
        if (payload.mode() != expectedMode)
            throw new LicenseException("payload mode disagrees with envelope");
        return payload;
    }

    public static boolean verify(byte[] data, byte[] signature, PublicKey key) {
        try {
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initVerify(key);
            s.update(data);
            return s.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }

    // ---------- 密钥编解码（PEM/Base64） ----------

    public static PrivateKey loadPrivateKey(String base64Pem) {
        try {
            byte[] der = decodeBase64Pem(base64Pem);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new LicenseException("private key not loadable", e);
        }
    }

    public static PublicKey loadPublicKey(String base64Pem) {
        try {
            byte[] der = decodeBase64Pem(base64Pem);
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new LicenseException("public key not loadable", e);
        }
    }

    public static String toPem(String header, byte[] der) {
        String b64 = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(der);
        return "-----BEGIN " + header + "-----\n" + b64 + "\n-----END " + header + "-----\n";
    }

    private static byte[] decodeBase64Pem(String pem) {
        return Base64.getMimeDecoder().decode(pem.replaceAll("-----BEGIN [^-]+-----", "")
                .replaceAll("-----END [^-]+-----", ""));
    }

    // ---------- XML 信封 ----------

    static String toXml(String canonicalData, byte[] sig, int mode) {
        // 规范串字符集已由 LicensePayload 白名单限定（无 < > &），可原样嵌入 CDATA
        return "<License>\n"
                + "  <Data>" + xmlEscapePayload(canonicalData) + "</Data>\n"
                + "  <Signature mode=\"" + mode + "\">"
                + Base64.getEncoder().encodeToString(sig) + "</Signature>\n"
                + "</License>\n";
    }

    private static String xmlEscapePayload(String canonical) {
        return canonical.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static Envelope parseXml(String xml) {
        try {
            DocumentBuilderFactory dbf = secureFactory();
            javax.xml.parsers.DocumentBuilder builder = dbf.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                @Override public void warning(org.xml.sax.SAXParseException e) {}
                @Override public void error(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXParseException { throw e; }
                @Override public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXParseException { throw e; }
            });
            Document doc = builder.parse(new InputSource(new StringReader(xml)));
            Element root = doc.getDocumentElement();
            if (!"License".equals(root.getNodeName()))
                throw new LicenseException("Invalid license format.");
            Element data = childElement(root, "Data");
            Element signature = childElement(root, "Signature");
            if (data == null || signature == null)
                throw new LicenseException("Invalid license format.");
            String canonical = unescape(data.getTextContent());
            byte[] sig = Base64.getDecoder().decode(signature.getTextContent().trim());
            int mode = Integer.parseInt(signature.getAttribute("mode"));
            return new Envelope(canonical, sig, mode);
        } catch (LicenseException le) {
            throw le;
        } catch (Exception e) {
            throw new LicenseException("Invalid license format.", e);
        }
    }

    private static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }

    private static DocumentBuilderFactory secureFactory() throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        dbf.setExpandEntityReferences(false);
        return dbf;
    }

    private static Element childElement(Element parent, String name) {
        NodeList list = parent.getElementsByTagName(name);
        for (int i = 0; i < list.getLength(); i++)
            if (list.item(i).getParentNode() == parent)
                return (Element) list.item(i);
        return null;
    }
}
