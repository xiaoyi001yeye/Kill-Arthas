package com.fordring.target;

import com.fordring.common.enums.ArthasStatus;
import com.fordring.common.enums.AuthType;
import com.fordring.common.enums.TargetType;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "access_target")
public class AccessTarget {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public String name;
    public String environment;
    public String host;
    public Integer sshPort;

    @Enumerated(EnumType.STRING)
    public AuthType authType;

    public String username;
    public Long credentialId;

    @Enumerated(EnumType.STRING)
    public TargetType targetType;

    public String containerName;
    public Long processId;
    public String processName;

    @Enumerated(EnumType.STRING)
    public ArthasStatus arthasStatus;

    public Integer telnetPort;
    public Integer httpPort;
    public Instant latestOperationTime;
    public String latestFailureReason;
    public String createdByName;
    public Instant createdAt;
    public Instant updatedAt;
}
