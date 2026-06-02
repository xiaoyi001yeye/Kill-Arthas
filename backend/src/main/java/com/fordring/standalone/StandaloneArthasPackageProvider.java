package com.fordring.standalone;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

@Component
@ConditionalOnProperty(name = "fordring.standalone.enabled", havingValue = "true")
public class StandaloneArthasPackageProvider {
    public static final String VERSION = "4.2.0";
    public static final String SHA256 = "2489d42868d2d71dac3893fc6c4b01cae380597eb0cd33ea36f4b243cfd9e011";
    private static final String RESOURCE = "/tools/arthas-bin.zip";

    public ExtractedPackage extract() throws IOException {
        try (InputStream input = getClass().getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IOException("standalone JAR 缺少资源 " + RESOURCE);
            }
            var path = Files.createTempFile("fordring-arthas-bin-", ".zip");
            try {
                Files.copy(input, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                var actual = sha256(path);
                if (!SHA256.equals(actual)) {
                    throw new IOException("standalone JAR 内 Arthas 包 SHA-256 校验失败");
                }
                return new ExtractedPackage(path, actual);
            } catch (Exception error) {
                Files.deleteIfExists(path);
                throw error;
            }
        }
    }

    private String sha256(Path path) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                input.transferTo(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(), digest));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("JVM 不支持 SHA-256", error);
        }
    }

    public record ExtractedPackage(Path path, String sha256) {
    }
}
