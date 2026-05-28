package com.fordring.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fordring")
public class FordringProperties {
    public final Operator operator = new Operator();
    public final Command command = new Command();
    public final Arthas arthas = new Arthas();
    public final Access access = new Access();
    public final Cors cors = new Cors();

    public static class Operator {
        public String defaultName = "admin";
    }

    public static class Command {
        public int defaultTimeoutSeconds = 30;
        public long outputMaxBytes = 1_048_576;
        public int outputChunkBytes = 8192;
    }

    public static class Arthas {
        public int defaultTelnetPort = 3658;
        public int defaultHttpPort = 8563;
        public String username = "arthas";
        public String password = "fordring_dev";
    }

    public static class Access {
        public int defaultSshPort = 22;
    }

    public static class Cors {
        public String allowedOrigins = "http://localhost:5173";
    }
}
