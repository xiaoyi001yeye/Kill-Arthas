package com.fordring.standalone;

import com.fordring.FordringApplication;
import com.fordring.commandhistory.CommandHistoryImportController;
import com.fordring.commandhistory.CommandHistoryQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StandaloneContextTest {
    @Test
    void startsWithH2MigrationsAndPortableHistoryQuery() {
        try (var context = standaloneContext()) {
            var result = context.getBean(CommandHistoryQueryService.class)
                    .list(null, null, null, null, null, 1, 10);
            assertEquals(0, result.total());
            assertTrue(result.items().isEmpty());
        }
    }

    @Test
    void doesNotRegisterCommandHistoryImportController() {
        try (var context = standaloneContext()) {
            assertTrue(context.getBeansOfType(CommandHistoryImportController.class).isEmpty());
        }
    }

    private org.springframework.context.ConfigurableApplicationContext standaloneContext() {
        return new SpringApplicationBuilder(FordringApplication.class)
                .profiles("standalone")
                .web(WebApplicationType.NONE)
                .properties("spring.main.banner-mode=off")
                .run();
    }
}
