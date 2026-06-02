package com.fordring.standalone;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@ConditionalOnProperty(name = "fordring.standalone.enabled", havingValue = "true")
public class StandaloneSpaConfig implements WebMvcConfigurer {
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
        registry.addViewController("/access").setViewName("forward:/index.html");
        registry.addViewController("/console").setViewName("forward:/index.html");
        registry.addViewController("/console/{targetId}").setViewName("forward:/index.html");
        registry.addViewController("/commands").setViewName("forward:/index.html");
        registry.addViewController("/commands/{executionId}").setViewName("forward:/index.html");
    }
}
