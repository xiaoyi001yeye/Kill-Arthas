package com.fordring.credential;

import com.fordring.common.enums.AuthType;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

@Service
public class CredentialService {
    private final CredentialSecretRepository repository;

    public CredentialService(CredentialSecretRepository repository) {
        this.repository = repository;
    }

    public Long save(String name, AuthType authType, String secret) {
        if (secret == null || secret.isBlank()) {
            return null;
        }
        var now = Instant.now();
        var credential = new CredentialSecret();
        credential.name = name == null || name.isBlank() ? authType.name().toLowerCase() + "-credential" : name;
        credential.authType = authType;
        credential.encryptedPayload = Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8));
        credential.algorithm = "BASE64_DEV_ONLY";
        credential.iv = "dev";
        credential.keyVersion = "0.1.0-dev";
        credential.maskedSummary = "******";
        credential.createdAt = now;
        credential.updatedAt = now;
        return repository.save(credential).id;
    }

    public String reveal(Long credentialId) {
        if (credentialId == null) {
            return null;
        }
        var credential = repository.findById(credentialId)
                .orElseThrow(() -> new IllegalArgumentException("凭据不存在"));
        if (!"BASE64_DEV_ONLY".equals(credential.algorithm)) {
            throw new IllegalStateException("不支持的凭据加密算法");
        }
        return new String(Base64.getDecoder().decode(credential.encryptedPayload), StandardCharsets.UTF_8);
    }
}
