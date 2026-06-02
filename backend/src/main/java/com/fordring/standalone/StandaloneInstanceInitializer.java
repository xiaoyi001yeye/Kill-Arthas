package com.fordring.standalone;

import com.fordring.config.FordringProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "fordring.standalone.enabled", havingValue = "true")
public class StandaloneInstanceInitializer {
    public StandaloneInstanceInitializer(FordringProperties properties) {
        var generatedId = System.getProperty("FORDRING_INSTANCE_ID");
        if (generatedId != null && !generatedId.isBlank()) {
            properties.instance.id = generatedId;
        }
        var generatedName = System.getProperty("FORDRING_INSTANCE_NAME");
        if (generatedName != null && !generatedName.isBlank()) {
            properties.instance.name = generatedName;
        }
    }
}
