package com.fordring.credential;

import com.fordring.common.enums.AuthType;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "credential_secret")
public class CredentialSecret {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public String name;

    @Enumerated(EnumType.STRING)
    public AuthType authType;

    @Column(columnDefinition = "text")
    public String encryptedPayload;

    public String algorithm;
    public String iv;
    public String keyVersion;
    public String maskedSummary;
    public Instant createdAt;
    public Instant updatedAt;
}

