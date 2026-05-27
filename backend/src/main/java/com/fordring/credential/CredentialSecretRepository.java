package com.fordring.credential;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CredentialSecretRepository extends JpaRepository<CredentialSecret, Long> {
}

