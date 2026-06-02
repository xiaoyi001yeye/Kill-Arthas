package com.fordring.standalone;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class StandaloneArthasOwnershipTracker {
    private final Set<Long> attachedByThisSession = ConcurrentHashMap.newKeySet();

    public void record(Long targetId, boolean attachedByCurrentRequest) {
        if (attachedByCurrentRequest) {
            attachedByThisSession.add(targetId);
        } else {
            attachedByThisSession.remove(targetId);
        }
    }

    public void remove(Long targetId) {
        attachedByThisSession.remove(targetId);
    }

    public Set<Long> snapshot() {
        return Set.copyOf(attachedByThisSession);
    }
}
