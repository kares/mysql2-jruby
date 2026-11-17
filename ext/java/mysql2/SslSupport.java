// SPDX-FileCopyrightText: Copyright (c) 2026 Karol Bucek
// SPDX-License-Identifier: LGPL-3.0-or-later

package mysql2;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mysql.cj.conf.PropertyDefinitions.SslMode;

import org.jruby.RubyFixnum;
import org.jruby.RubyHash;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;

/**
 * SSL/TLS configuration and PEM-to-PKCS12 keystore bridging for Mysql2::Client.
 *
 * Bridges C mysql2 / libmysqlclient SSL options (:ssl_mode, :sslca, :sslcapath,
 * :sslcert, :sslkey, :sslcipher, :sslverify) with MySQL Connector/J's JSSE
 * properties while preserving full JDBC keystore URL compatibility.
 */
final class SslSupport {

    private static final String KEYSTORE_TYPE = "PKCS12";
    private static final char[] KEYSTORE_PASSWORD = "mysql2changeit".toCharArray();
    private static final String KEYSTORE_PASSWORD_STR = new String(KEYSTORE_PASSWORD);

    private static final Map<String, String> TRUSTSTORE_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, String> KEYSTORE_CACHE = new ConcurrentHashMap<>();

    private SslSupport() {}

    static void configureSsl(ThreadContext context, RubyHash opts, Properties jdbcProperties, Map<String, String> urlProperties) {
        String sslKey = Client.str(Client.opt(context, opts, "sslkey"));
        String sslCert = Client.str(Client.opt(context, opts, "sslcert"));
        String sslCa = Client.str(Client.opt(context, opts, "sslca"));
        String sslCaPath = Client.str(Client.opt(context, opts, "sslcapath"));
        String sslCipher = Client.str(Client.opt(context, opts, "sslcipher"));
        IRubyObject sslVerifyObj = Client.opt(context, opts, "sslverify");
        IRubyObject sslModeObj = Client.opt(context, opts, "ssl_mode");

        boolean hasVerifyFlag = Client.hasFlag(context, opts, "SSL_VERIFY_SERVER_CERT", Client.SSL_VERIFY_SERVER_CERT);
        boolean hasSslFlag = Client.hasFlag(context, opts, "SSL", Client.SSL);

        SslMode explicitMode = parseSslMode(context, sslModeObj);
        Boolean sslVerify = sslVerifyObj.isNil() ? null : sslVerifyObj.isTrue();

        // 1. Determine effective sslMode
        SslMode mode = explicitMode;
        if (mode == null) {
            if (Boolean.TRUE.equals(sslVerify) || hasVerifyFlag) {
                mode = SslMode.VERIFY_IDENTITY;
            } else if (Boolean.FALSE.equals(sslVerify)) {
                if (sslCa != null || sslCaPath != null || sslKey != null || sslCert != null || sslCipher != null || hasSslFlag) {
                    mode = SslMode.REQUIRED;
                }
            } else if (sslCa != null || sslCaPath != null) {
                mode = SslMode.VERIFY_CA;
            } else if (sslKey != null || sslCert != null || sslCipher != null || hasSslFlag) {
                mode = SslMode.REQUIRED;
            }
        }

        if (mode != null) {
            jdbcProperties.setProperty("sslMode", mode.name());
        } else if (!hasExplicitSslProperty(jdbcProperties, urlProperties)) {
            jdbcProperties.setProperty("sslMode", SslMode.DISABLED.name());
        }

        // 2. Configure trust store from :sslca and/or :sslcapath
        if (sslCa != null || sslCaPath != null) {
            configureTrustStore(context, sslCa, sslCaPath, jdbcProperties);
        }

        // 3. Configure client certificate keystore from :sslcert and/or :sslkey
        if (sslCert != null || sslKey != null) {
            configureClientKeyStore(context, sslCert, sslKey, jdbcProperties);
        }

        // 4. Configure cipher suites from :sslcipher
        if (sslCipher != null && !sslCipher.trim().isEmpty()) {
            String mappedCiphers = mapCipherSuites(sslCipher);
            if (!mappedCiphers.isEmpty()) {
                jdbcProperties.setProperty("tlsCiphersuites", mappedCiphers);
            }
        }
    }

    private static boolean hasExplicitSslProperty(Properties props, Map<String, String> urlProps) {
        return hasKeyIgnoreCase(props, urlProps, "sslMode")
                || hasKeyIgnoreCase(props, urlProps, "useSSL")
                || hasKeyIgnoreCase(props, urlProps, "requireSSL")
                || hasKeyIgnoreCase(props, urlProps, "verifyServerCertificate");
    }

    static boolean hasKeyIgnoreCase(Properties props, Map<String, String> urlProps, String key) {
        if (props != null) {
            for (String k : props.stringPropertyNames()) {
                if (k.equalsIgnoreCase(key)) return true;
            }
        }
        if (urlProps != null) {
            for (String k : urlProps.keySet()) {
                if (k.equalsIgnoreCase(key)) return true;
            }
        }
        return false;
    }

    /**
     * Parse :ssl_mode matching C mysql2's Client#parse_ssl_mode and rb_set_ssl_mode_option:
     * accepts integer constants (1..5), symbols/strings (:disabled, :preferred, :required,
     * :verify_ca, :verify_identity, "SSL_MODE_REQUIRED", etc., case-insensitively).
     */
    static SslMode parseSslMode(ThreadContext context, IRubyObject modeObj) {
        if (modeObj == null || modeObj.isNil()) return null;

        if (modeObj instanceof RubyFixnum) {
            long val = ((RubyFixnum) modeObj).getLongValue();
            switch ((int) val) {
                case 1: return SslMode.DISABLED;
                case 2: return SslMode.PREFERRED;
                case 3: return SslMode.REQUIRED;
                case 4: return SslMode.VERIFY_CA;
                case 5: return SslMode.VERIFY_IDENTITY;
                default:
                    throw Client.error(context, "ssl_mode= takes DISABLED, PREFERRED, REQUIRED, VERIFY_CA, VERIFY_IDENTITY, you passed: " + val);
            }
        }

        String raw = modeObj.toString();
        String m = raw.trim().toUpperCase(Locale.ROOT);
        if (m.startsWith("SSL_MODE_")) {
            m = m.substring("SSL_MODE_".length());
        }
        switch (m) {
            case "DISABLED":        return SslMode.DISABLED;
            case "PREFERRED":       return SslMode.PREFERRED;
            case "REQUIRED":        return SslMode.REQUIRED;
            case "VERIFY_CA":       return SslMode.VERIFY_CA;
            case "VERIFY_IDENTITY": return SslMode.VERIFY_IDENTITY;
            default:
                Client.warn(context, "Unknown MySQL ssl_mode flag: " + raw);
                return null;
        }
    }

    private static void configureTrustStore(ThreadContext context, String sslCa, String sslCaPath, Properties jdbcProperties) {
        // AR-JDBC compatibility: if sslCa is a keystore URL or non-PEM keystore file (.jks, .p12, .pfx)
        // and no sslCaPath directory is given, pass it straight to Connector/J as a keystore URL
        if (sslCa != null && sslCaPath == null && isKeyStoreResource(sslCa)) {
            jdbcProperties.setProperty("trustCertificateKeyStoreUrl", fileUrl(sslCa));
            String ksType = inferKeyStoreType(sslCa);
            if (ksType != null) jdbcProperties.putIfAbsent("trustCertificateKeyStoreType", ksType);
            jdbcProperties.putIfAbsent("fallbackToSystemTrustStore", "false");
            return;
        }

        try {
            String trustStoreUrl = buildPemTrustStoreUrl(sslCa, sslCaPath);
            jdbcProperties.setProperty("trustCertificateKeyStoreUrl", trustStoreUrl);
            jdbcProperties.setProperty("trustCertificateKeyStoreType", KEYSTORE_TYPE);
            jdbcProperties.setProperty("trustCertificateKeyStorePassword", KEYSTORE_PASSWORD_STR);
            jdbcProperties.putIfAbsent("fallbackToSystemTrustStore", "false");
        } catch (Exception e) {
            throw Client.error(context, "SSL error: Unable to get certificate from '"
                    + (sslCa != null ? sslCa : sslCaPath) + "': " + e.getMessage());
        }
    }

    private static void configureClientKeyStore(ThreadContext context, String sslCert, String sslKey, Properties jdbcProperties) {
        // AR-JDBC compatibility: if sslCert (or sslKey when sslCert is absent) is a Java keystore
        String candidate = sslCert != null ? sslCert : sslKey;
        if ((sslKey == null || sslCert == null) && isKeyStoreResource(candidate)) {
            jdbcProperties.setProperty("clientCertificateKeyStoreUrl", fileUrl(candidate));
            String ksType = inferKeyStoreType(candidate);
            if (ksType != null) jdbcProperties.putIfAbsent("clientCertificateKeyStoreType", ksType);
            return;
        }
        if (sslCert != null && sslKey != null && isKeyStoreResource(sslCert) && !isPemFile(sslKey)) {
            jdbcProperties.setProperty("clientCertificateKeyStoreUrl", fileUrl(sslCert));
            String ksType = inferKeyStoreType(sslCert);
            if (ksType != null) jdbcProperties.putIfAbsent("clientCertificateKeyStoreType", ksType);
            return;
        }

        if (sslCert == null || sslKey == null) {
            // If only one of PEM cert or PEM key is given, check if the provided one is a keystore;
            // otherwise both cert and key are needed to build a client PKCS12 entry
            if (!isPemFile(candidate)) {
                jdbcProperties.setProperty("clientCertificateKeyStoreUrl", fileUrl(candidate));
                String ksType = inferKeyStoreType(candidate);
                if (ksType != null) jdbcProperties.putIfAbsent("clientCertificateKeyStoreType", ksType);
                return;
            }
            throw Client.error(context, "SSL error: both :sslcert and :sslkey are required when using PEM files");
        }

        try {
            String keyStoreUrl = buildPemClientKeyStoreUrl(sslCert, sslKey);
            jdbcProperties.setProperty("clientCertificateKeyStoreUrl", keyStoreUrl);
            jdbcProperties.setProperty("clientCertificateKeyStoreType", KEYSTORE_TYPE);
            jdbcProperties.setProperty("clientCertificateKeyStorePassword", KEYSTORE_PASSWORD_STR);
        } catch (Exception e) {
            throw Client.error(context, "SSL error: Unable to load client certificate/key: " + e.getMessage());
        }
    }

    private static String inferKeyStoreType(String pathOrUrl) {
        if (pathOrUrl == null) return null;
        String lower = pathOrUrl.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".p12") || lower.endsWith(".pfx")) return "PKCS12";
        if (lower.endsWith(".jks")) return "JKS";
        return null;
    }

    private static boolean isKeyStoreResource(String pathOrUrl) {
        if (pathOrUrl == null) return false;
        String lower = pathOrUrl.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jks") || lower.endsWith(".p12") || lower.endsWith(".pfx") || lower.endsWith(".keystore")) {
            return true;
        }
        File file = resolveFile(pathOrUrl);
        if (file != null && file.isFile()) {
            return !isPemFile(file);
        }
        // If it's a non-file URL (e.g. classpath: or http:) treat as keystore URL
        return lower.contains("://") && !lower.startsWith("file:");
    }

    private static File resolveFile(String pathOrUrl) {
        if (pathOrUrl == null) return null;
        if (pathOrUrl.startsWith("file:")) {
            try {
                return new File(new java.net.URI(pathOrUrl));
            } catch (Exception e) {
                return new File(pathOrUrl.substring(5));
            }
        }
        return new File(pathOrUrl);
    }

    private static boolean isPemFile(String pathOrUrl) {
        File f = resolveFile(pathOrUrl);
        return f != null && f.isFile() && isPemFile(f);
    }

    private static boolean isPemFile(File file) {
        byte[] buf = new byte[256];
        try (InputStream in = new FileInputStream(file)) {
            int n = in.read(buf);
            if (n <= 0) return false;
            String head = new String(buf, 0, n, StandardCharsets.US_ASCII);
            return head.contains("-----BEGIN ");
        } catch (IOException e) {
            return false;
        }
    }

    private static String buildPemTrustStoreUrl(String sslCa, String sslCaPath) throws IOException, GeneralSecurityException {
        List<File> pemFiles = new ArrayList<>();
        StringBuilder cacheKey = new StringBuilder();

        if (sslCa != null) {
            File caFile = resolveFile(sslCa);
            if (caFile == null || !caFile.isFile()) {
                throw new IOException("CA file not found: " + sslCa);
            }
            pemFiles.add(caFile);
            cacheKey.append("ca:").append(caFile.getAbsolutePath()).append('@').append(caFile.lastModified()).append(';');
        }

        if (sslCaPath != null) {
            File caDir = resolveFile(sslCaPath);
            if (caDir == null || !caDir.isDirectory()) {
                throw new IOException("CA directory not found: " + sslCaPath);
            }
            File[] files = caDir.listFiles(f -> f.isFile() && isPemFile(f));
            if (files != null) {
                Arrays.sort(files);
                for (File f : files) {
                    pemFiles.add(f);
                    cacheKey.append("dir:").append(f.getAbsolutePath()).append('@').append(f.lastModified()).append(';');
                }
            }
        }

        String key = cacheKey.toString();
        String cachedUrl = TRUSTSTORE_CACHE.get(key);
        if (cachedUrl != null && resolveFile(cachedUrl) != null && resolveFile(cachedUrl).isFile()) {
            return cachedUrl;
        }

        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        KeyStore ks = KeyStore.getInstance(KEYSTORE_TYPE);
        ks.load(null, KEYSTORE_PASSWORD);

        int index = 0;
        for (File pemFile : pemFiles) {
            byte[] bytes = Files.readAllBytes(pemFile.toPath());
            try (InputStream in = new ByteArrayInputStream(bytes)) {
                Collection<? extends Certificate> certs = cf.generateCertificates(in);
                for (Certificate cert : certs) {
                    ks.setCertificateEntry("ca-" + (index++), cert);
                }
            }
        }

        if (index == 0) {
            throw new GeneralSecurityException("No X.509 certificates found");
        }

        String url = writeTempKeyStore(ks, "mysql2-trust-");
        TRUSTSTORE_CACHE.put(key, url);
        return url;
    }

    private static String buildPemClientKeyStoreUrl(String sslCert, String sslKey) throws IOException, GeneralSecurityException {
        File certFile = resolveFile(sslCert);
        File keyFile = resolveFile(sslKey);
        if (certFile == null || !certFile.isFile()) {
            throw new IOException("Client certificate file not found: " + sslCert);
        }
        if (keyFile == null || !keyFile.isFile()) {
            throw new IOException("Client private key file not found: " + sslKey);
        }

        String cacheKey = certFile.getAbsolutePath() + "@" + certFile.lastModified()
                + ";" + keyFile.getAbsolutePath() + "@" + keyFile.lastModified();
        String cachedUrl = KEYSTORE_CACHE.get(cacheKey);
        if (cachedUrl != null && resolveFile(cachedUrl) != null && resolveFile(cachedUrl).isFile()) {
            return cachedUrl;
        }

        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        Collection<? extends Certificate> certs;
        try (InputStream in = new ByteArrayInputStream(Files.readAllBytes(certFile.toPath()))) {
            certs = cf.generateCertificates(in);
        }
        if (certs.isEmpty()) {
            throw new GeneralSecurityException("No X.509 certificate found in " + sslCert);
        }

        PrivateKey privateKey = loadPrivateKey(keyFile);

        KeyStore ks = KeyStore.getInstance(KEYSTORE_TYPE);
        ks.load(null, KEYSTORE_PASSWORD);
        ks.setKeyEntry("client", privateKey, KEYSTORE_PASSWORD, certs.toArray(new Certificate[0]));

        String url = writeTempKeyStore(ks, "mysql2-client-");
        KEYSTORE_CACHE.put(cacheKey, url);
        return url;
    }

    static PrivateKey loadPrivateKey(File keyFile) throws IOException, GeneralSecurityException {
        final Pattern PEM_BLOCK_PATTERN = Pattern.compile(
                "-----BEGIN ([A-Z0-9 ]+)-----\\s*([A-Za-z0-9+/=\\r\\n\\s]+?)\\s*-----END \\1-----");
        String content = Files.readString(keyFile.toPath(), StandardCharsets.US_ASCII);
        Matcher matcher = PEM_BLOCK_PATTERN.matcher(content);
        while (matcher.find()) {
            String type = matcher.group(1).trim();
            String base64 = matcher.group(2).replaceAll("[^A-Za-z0-9+/=]", "");
            byte[] der = Base64.getDecoder().decode(base64);

            switch (type) {
                case "PRIVATE KEY":
                    return generatePkcs8PrivateKey(der);
                case "RSA PRIVATE KEY":
                    return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(wrapPkcs1RsaInPkcs8(der)));
                case "EC PRIVATE KEY":
                    // Skip EC PARAMETERS block if present; only parse EC PRIVATE KEY
                    return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(wrapSec1EcInPkcs8(der)));
                default:
                    // Ignore non-key blocks such as EC PARAMETERS
                    break;
            }
        }
        throw new GeneralSecurityException("No supported unencrypted PEM private key found in " + keyFile);
    }

    private static PrivateKey generatePkcs8PrivateKey(byte[] pkcs8Der) throws GeneralSecurityException {
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(pkcs8Der);
        for (String alg : new String[] { "RSA", "EC", "DSA", "Ed25519" }) {
            try {
                return KeyFactory.getInstance(alg).generatePrivate(spec);
            } catch (InvalidKeySpecException ignore) {
                // try next algorithm
            }
        }
        throw new InvalidKeySpecException("Unsupported PKCS#8 private key algorithm");
    }

    // OID 1.2.840.113549.1.1.1 (rsaEncryption) + NULL: 30 0d 06 09 2a 86 48 86 f7 0d 01 01 01 05 00
    private static final byte[] RSA_ALGORITHM_ID = new byte[] {
        0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00
    };

    /**
     * Wrap a PKCS#1 RSAPrivateKey DER structure into a PKCS#8 PrivateKeyInfo DER structure:
     * SEQUENCE { version INTEGER (0), AlgorithmIdentifier (rsaEncryption), OCTET STRING (pkcs1Der) }
     */
    private static byte[] wrapPkcs1RsaInPkcs8(byte[] pkcs1Der) {
        byte[] version = new byte[] { 0x02, 0x01, 0x00 };
        byte[] octetString = derEncode((byte) 0x04, pkcs1Der);
        byte[] body = concat(version, RSA_ALGORITHM_ID, octetString);
        return derEncode((byte) 0x30, body);
    }

    /**
     * Wrap a SEC1 ECPrivateKey DER structure into PKCS#8 when namedCurve [0] OID is embedded.
     */
    private static byte[] wrapSec1EcInPkcs8(byte[] sec1Der) throws GeneralSecurityException {
        // Locate context-specific tag [0] (0xA0) containing the curve OID
        byte[] curveOid = extractSec1CurveOid(sec1Der);
        if (curveOid == null) {
            throw new InvalidKeySpecException("EC PRIVATE KEY does not contain namedCurve OID; convert to PKCS#8");
        }
        // id-ecPublicKey OID 1.2.840.10045.2.1: 06 07 2a 86 48 ce 3d 02 01
        byte[] ecPubOid = new byte[] { 0x06, 0x07, 0x2a, (byte) 0x86, 0x48, (byte) 0xce, 0x3d, 0x02, 0x01 };
        byte[] algId = derEncode((byte) 0x30, concat(ecPubOid, curveOid));
        byte[] version = new byte[] { 0x02, 0x01, 0x00 };
        byte[] octetString = derEncode((byte) 0x04, sec1Der);
        return derEncode((byte) 0x30, concat(version, algId, octetString));
    }

    private static byte[] extractSec1CurveOid(byte[] sec1Der) {
        for (int i = 0; i + 4 < sec1Der.length; i++) {
            if ((sec1Der[i] & 0xFF) == 0xA0 && (sec1Der[i + 2] & 0xFF) == 0x06) {
                int oidLen = sec1Der[i + 3] & 0xFF;
                if (i + 4 + oidLen <= sec1Der.length) {
                    return Arrays.copyOfRange(sec1Der, i + 2, i + 4 + oidLen);
                }
            }
        }
        return null;
    }

    private static byte[] derEncode(byte tag, byte[] content) {
        int len = content.length;
        byte[] header;
        if (len < 128) {
            header = new byte[] { tag, (byte) len };
        } else if (len < 256) {
            header = new byte[] { tag, (byte) 0x81, (byte) len };
        } else if (len < 65536) {
            header = new byte[] { tag, (byte) 0x82, (byte) (len >> 8), (byte) len };
        } else {
            header = new byte[] { tag, (byte) 0x83, (byte) (len >> 16), (byte) (len >> 8), (byte) len };
        }
        return concat(header, content);
    }

    private static byte[] concat(byte[]... arrays) {
        int total = 0;
        for (byte[] a : arrays) total += a.length;
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, out, pos, a.length);
            pos += a.length;
        }
        return out;
    }

    private static String writeTempKeyStore(KeyStore ks, String prefix) throws IOException, GeneralSecurityException {
        Path tempPath;
        try {
            Set<PosixFilePermission> perms = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            tempPath = Files.createTempFile(prefix, ".p12", PosixFilePermissions.asFileAttribute(perms));
        } catch (UnsupportedOperationException e) {
            tempPath = Files.createTempFile(prefix, ".p12");
        }
        File tempFile = tempPath.toFile();
        tempFile.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(tempFile)) {
            ks.store(out, KEYSTORE_PASSWORD);
        }
        return fileUrl(tempFile.getAbsolutePath());
    }

    static String fileUrl(String path) {
        if (path.contains("://") || path.startsWith("file:")) return path;
        try {
            return new File(path).toURI().toURL().toString();
        } catch (MalformedURLException e) {
            return path;
        }
    }

    // --- OpenSSL <-> IANA / JSSE cipher suite mapping -----------------------

    private static final String[][] CIPHER_PAIRS = {
        // TLS 1.3 (same name in OpenSSL and IANA/JSSE)
        { "TLS_AES_256_GCM_SHA384",                  "TLS_AES_256_GCM_SHA384" },
        { "TLS_AES_128_GCM_SHA256",                  "TLS_AES_128_GCM_SHA256" },
        { "TLS_CHACHA20_POLY1305_SHA256",            "TLS_CHACHA20_POLY1305_SHA256" },
        { "TLS_AES_128_CCM_SHA256",                  "TLS_AES_128_CCM_SHA256" },
        { "TLS_AES_128_CCM_8_SHA256",                "TLS_AES_128_CCM_8_SHA256" },
        // TLS 1.2 ECDHE
        { "ECDHE-ECDSA-AES128-GCM-SHA256",           "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256" },
        { "ECDHE-ECDSA-AES256-GCM-SHA384",           "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384" },
        { "ECDHE-RSA-AES128-GCM-SHA256",             "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256" },
        { "ECDHE-RSA-AES256-GCM-SHA384",             "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384" },
        { "ECDHE-ECDSA-CHACHA20-POLY1305",           "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256" },
        { "ECDHE-RSA-CHACHA20-POLY1305",             "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256" },
        { "ECDHE-ECDSA-AES256-CCM",                  "TLS_ECDHE_ECDSA_WITH_AES_256_CCM" },
        { "ECDHE-ECDSA-AES128-CCM",                  "TLS_ECDHE_ECDSA_WITH_AES_128_CCM" },
        { "ECDHE-ECDSA-AES256-CCM8",                 "TLS_ECDHE_ECDSA_WITH_AES_256_CCM_8" },
        { "ECDHE-ECDSA-AES128-CCM8",                 "TLS_ECDHE_ECDSA_WITH_AES_128_CCM_8" },
        { "ECDHE-ECDSA-AES128-SHA256",               "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256" },
        { "ECDHE-RSA-AES128-SHA256",                 "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256" },
        { "ECDHE-ECDSA-AES256-SHA384",               "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384" },
        { "ECDHE-RSA-AES256-SHA384",                 "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384" },
        { "ECDHE-ECDSA-AES128-SHA",                  "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA" },
        { "ECDHE-RSA-AES128-SHA",                    "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA" },
        { "ECDHE-ECDSA-AES256-SHA",                  "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA" },
        { "ECDHE-RSA-AES256-SHA",                    "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA" },
        // TLS 1.2 DHE
        { "DHE-RSA-AES128-GCM-SHA256",               "TLS_DHE_RSA_WITH_AES_128_GCM_SHA256" },
        { "DHE-RSA-AES256-GCM-SHA384",               "TLS_DHE_RSA_WITH_AES_256_GCM_SHA384" },
        { "DHE-RSA-AES256-CCM",                      "TLS_DHE_RSA_WITH_AES_256_CCM" },
        { "DHE-RSA-AES128-CCM",                      "TLS_DHE_RSA_WITH_AES_128_CCM" },
        { "DHE-RSA-AES256-CCM8",                     "TLS_DHE_RSA_WITH_AES_256_CCM_8" },
        { "DHE-RSA-AES128-CCM8",                     "TLS_DHE_RSA_WITH_AES_128_CCM_8" },
        { "DHE-RSA-CHACHA20-POLY1305",               "TLS_DHE_RSA_WITH_CHACHA20_POLY1305_SHA256" },
        { "DHE-RSA-AES128-SHA256",                   "TLS_DHE_RSA_WITH_AES_128_CBC_SHA256" },
        { "DHE-RSA-AES256-SHA256",                   "TLS_DHE_RSA_WITH_AES_256_CBC_SHA256" },
        { "DHE-RSA-AES128-SHA",                      "TLS_DHE_RSA_WITH_AES_128_CBC_SHA" },
        { "DHE-RSA-AES256-SHA",                      "TLS_DHE_RSA_WITH_AES_256_CBC_SHA" },
        { "DHE-DSS-AES128-GCM-SHA256",               "TLS_DHE_DSS_WITH_AES_128_GCM_SHA256" },
        { "DHE-DSS-AES256-GCM-SHA384",               "TLS_DHE_DSS_WITH_AES_256_GCM_SHA384" },
        { "DHE-DSS-AES128-SHA256",                   "TLS_DHE_DSS_WITH_AES_128_CBC_SHA256" },
        { "DHE-DSS-AES256-SHA256",                   "TLS_DHE_DSS_WITH_AES_256_CBC_SHA256" },
        { "DHE-DSS-AES128-SHA",                      "TLS_DHE_DSS_WITH_AES_128_CBC_SHA" },
        { "DHE-DSS-AES256-SHA",                      "TLS_DHE_DSS_WITH_AES_256_CBC_SHA" },
        { "DHE-RSA-CAMELLIA256-SHA256",              "TLS_DHE_RSA_WITH_CAMELLIA_256_CBC_SHA256" },
        { "DHE-RSA-CAMELLIA128-SHA256",              "TLS_DHE_RSA_WITH_CAMELLIA_128_CBC_SHA256" },
        { "DHE-RSA-CAMELLIA256-SHA",                 "TLS_DHE_RSA_WITH_CAMELLIA_256_CBC_SHA" },
        { "DHE-RSA-CAMELLIA128-SHA",                 "TLS_DHE_RSA_WITH_CAMELLIA_128_CBC_SHA" },
        // ECDH
        { "ECDH-ECDSA-AES128-GCM-SHA256",            "TLS_ECDH_ECDSA_WITH_AES_128_GCM_SHA256" },
        { "ECDH-ECDSA-AES256-GCM-SHA384",            "TLS_ECDH_ECDSA_WITH_AES_256_GCM_SHA384" },
        { "ECDH-RSA-AES128-GCM-SHA256",              "TLS_ECDH_RSA_WITH_AES_128_GCM_SHA256" },
        { "ECDH-RSA-AES256-GCM-SHA384",              "TLS_ECDH_RSA_WITH_AES_256_GCM_SHA384" },
        { "ECDH-ECDSA-AES128-SHA256",                "TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA256" },
        { "ECDH-RSA-AES128-SHA256",                  "TLS_ECDH_RSA_WITH_AES_128_CBC_SHA256" },
        { "ECDH-ECDSA-AES256-SHA384",                "TLS_ECDH_ECDSA_WITH_AES_256_CBC_SHA384" },
        { "ECDH-RSA-AES256-SHA384",                  "TLS_ECDH_RSA_WITH_AES_256_CBC_SHA384" },
        { "ECDH-ECDSA-AES128-SHA",                   "TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA" },
        { "ECDH-ECDSA-AES256-SHA",                   "TLS_ECDH_ECDSA_WITH_AES_256_CBC_SHA" },
        { "ECDH-RSA-AES128-SHA",                     "TLS_ECDH_RSA_WITH_AES_128_CBC_SHA" },
        { "ECDH-RSA-AES256-SHA",                     "TLS_ECDH_RSA_WITH_AES_256_CBC_SHA" },
        // RSA
        { "AES128-GCM-SHA256",                       "TLS_RSA_WITH_AES_128_GCM_SHA256" },
        { "AES256-GCM-SHA384",                       "TLS_RSA_WITH_AES_256_GCM_SHA384" },
        { "AES128-CCM",                              "TLS_RSA_WITH_AES_128_CCM" },
        { "AES256-CCM",                              "TLS_RSA_WITH_AES_256_CCM" },
        { "AES128-CCM8",                             "TLS_RSA_WITH_AES_128_CCM_8" },
        { "AES256-CCM8",                             "TLS_RSA_WITH_AES_256_CCM_8" },
        { "AES128-SHA256",                           "TLS_RSA_WITH_AES_128_CBC_SHA256" },
        { "AES256-SHA256",                           "TLS_RSA_WITH_AES_256_CBC_SHA256" },
        { "AES128-SHA",                              "TLS_RSA_WITH_AES_128_CBC_SHA" },
        { "AES256-SHA",                              "TLS_RSA_WITH_AES_256_CBC_SHA" },
        { "CAMELLIA256-SHA",                         "TLS_RSA_WITH_CAMELLIA_256_CBC_SHA" },
        { "CAMELLIA128-SHA",                         "TLS_RSA_WITH_CAMELLIA_128_CBC_SHA" },
    };

    private static final Map<String, String> OPENSSL_TO_IANA = new HashMap<>();
    private static final Map<String, String> IANA_TO_OPENSSL = new HashMap<>();

    static {
        for (String[] pair : CIPHER_PAIRS) {
            OPENSSL_TO_IANA.put(pair[0], pair[1]);
            IANA_TO_OPENSSL.put(pair[1], pair[0]);
            if (pair[1].startsWith("TLS_")) {
                IANA_TO_OPENSSL.put("SSL_" + pair[1].substring(4), pair[0]);
            }
        }
    }

    /**
     * Map OpenSSL cipher list (colon, comma, or whitespace separated) or IANA/JSSE cipher names
     * to comma-separated IANA cipher names for Connector/J's tlsCiphersuites property.
     */
    static String mapCipherSuites(String cipherSpec) {
        if (cipherSpec == null) return "";
        String[] tokens = cipherSpec.trim().split("[,:\\s]+");
        Set<String> mapped = new LinkedHashSet<>();
        for (String token : tokens) {
            if (token.isEmpty() || token.startsWith("!")) continue;
            String iana = OPENSSL_TO_IANA.get(token);
            if (iana != null) {
                mapped.add(iana);
            } else {
                mapped.add(token);
            }
        }
        return String.join(",", mapped);
    }

    /**
     * Convert a JSSE/IANA cipher suite name back to the OpenSSL cipher name MySQL reports
     * in `SHOW STATUS LIKE 'Ssl_cipher'`.
     */
    static String toOpenSslCipherName(String jsseCipher) {
        if (jsseCipher == null) return null;
        return IANA_TO_OPENSSL.getOrDefault(jsseCipher, jsseCipher);
    }
}
