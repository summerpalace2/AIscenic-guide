package com.ai.guide;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 灵山智慧导游 - 启动类
 *
 * 启动流程：先加载 .env 环境变量（注入 System Properties）→ 再启动 Spring Boot。
 * 保证 Alibaba/DashScope/Redis 等外部服务配置在 Spring 容器初始化前已就绪。
 */
@SpringBootApplication
public class ScenicGuideApplication {

    public static void main(String[] args) {
        loadDotEnv();
        SpringApplication.run(ScenicGuideApplication.class, args);
    }

    /**
     * 加载 .env 文件（优先 ai/.env，兼容根目录 .env）
     */
    private static void loadDotEnv() {
        Path envPath = Paths.get("ai/.env");
        if (!Files.exists(envPath)) envPath = Paths.get(".env");
        if (!Files.exists(envPath)) {
            System.out.println("[提示] 未找到 .env 文件，跳过环境变量注入");
            return;
        }

        try {
            Files.lines(envPath)
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .filter(line -> line.contains("="))
                    .forEach(line -> {
                        int idx = line.indexOf("=");
                        String key = line.substring(0, idx).trim();
                        String value = line.substring(idx + 1).trim();
                        System.setProperty(key, value);
                    });
            System.out.println("[提示] 已从 .env 文件加载环境变量");
        } catch (IOException e) {
            System.err.println("[警告] 读取 .env 文件失败: " + e.getMessage());
        }
    }
}
