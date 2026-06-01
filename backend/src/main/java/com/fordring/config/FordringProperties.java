package com.fordring.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fordring")
public class FordringProperties {
    public final Instance instance = new Instance();
    public final Operator operator = new Operator();
    public final Command command = new Command();
    public final CommandHistory commandHistory = new CommandHistory();
    public final Arthas arthas = new Arthas();
    public final Access access = new Access();
    public final Cors cors = new Cors();

    public static class Instance {
        public String id = "fordring-local";
        public String name = "本地环境";
        public String baseUrl = "";
    }

    public static class Operator {
        public String defaultName = "admin";
    }

    public static class Command {
        public int defaultTimeoutSeconds = 30;
        public long outputMaxBytes = 1_048_576;
        public int outputChunkBytes = 8192;
    }

    public static class CommandHistory {
        public int exportMaxRecords = 500;
        public long exportMaxBytes = 52_428_800;
        public long importMaxBytes = 52_428_800;
        public long importMaxUncompressedBytes = 104_857_600;
        public int importMaxEntryCount = 5000;
        public int importPreviewTtlMinutes = 30;
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
