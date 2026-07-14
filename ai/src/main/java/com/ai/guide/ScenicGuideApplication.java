package com.ai.guide;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 景区导览AI后端启动类
 * 启动前从 .env 文件加载环境变量到系统属性
 */
@SpringBootApplication(exclude = {
        org.springframework.ai.autoconfigure.vectorstore.qdrant.QdrantVectorStoreAutoConfiguration.class
    })
public class ScenicGuideApplication {

    public static void main(String[] args) {
        loadDotEnv();
        SpringApplication.run(ScenicGuideApplication.class, args);
    }

    /**
     * 从 ai/.env 或 .env 读取环境变量并设入 System.getProperties()
     */
    private static void loadDotEnv() {
        String[] candidates = {"ai/.env", ".env"};
        Path envPath = null;
        for (String candidate : candidates) {
            Path p = Paths.get(candidate);
            if (Files.exists(p)) {
                envPath = p;
                break;
            }
        }

        if (envPath == null) {
            System.out.println("[提示] 未找到 .env 文件，跳过环境变量注入");
            return;
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(envPath.toFile()), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                // 跳过空行与注释
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eqIndex = line.indexOf('=');
                if (eqIndex > 0) {
                    String key = line.substring(0, eqIndex).trim();
                    String value = line.substring(eqIndex + 1).trim();
                    System.getProperties().setProperty(key, value);
                }
            }
            System.out.println("[提示] 已从 .env 文件加载环境变量");
        } catch (Exception e) {
            System.err.println("[警告] 读取 .env 文件失败: " + e.getMessage());
        }
    }
}
