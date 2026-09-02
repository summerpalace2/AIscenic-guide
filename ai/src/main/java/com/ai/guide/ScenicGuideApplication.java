package com.ai.guide;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 渝游智策景区导览 AI 核心后端启动入口
 *
 * 架构定位：系统唯一业务后端入口（Single Source of Truth），负责文旅智能排程、RAG知识检索、
 * 用户认证授权与偏好画像管理、多版本正式行程维护及运营监控。
 */
@EnableScheduling
@SpringBootApplication(exclude = {
        org.springframework.ai.autoconfigure.vectorstore.qdrant.QdrantVectorStoreAutoConfiguration.class
    })
public class ScenicGuideApplication {

    private static final Logger log = LoggerFactory.getLogger(ScenicGuideApplication.class);
    private static final Path CANONICAL_ENV_FILE = Paths.get("D:\\scenic-guide\\ai\\.env");

    public static void main(String[] args) {
        loadDotEnv();
        SpringApplication.run(ScenicGuideApplication.class, args);
    }

    /**
     * 读取唯一的本地开发配置源。进程环境和 JVM 参数始终优先，
     * 但不会再按工作目录探测 .env.local、根目录 .env 或其他文件。
     */
    public static void loadDotEnv() {
        if (!Files.exists(CANONICAL_ENV_FILE)) {
            log.info("[提示] 未找到 canonical 本地配置文件，跳过环境变量注入");
            return;
        }

        int loadedVariables = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(CANONICAL_ENV_FILE.toFile()), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.replaceFirst("^\\uFEFF", "").trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eqIndex = line.indexOf('=');
                if (eqIndex <= 0) continue;
                String key = line.substring(0, eqIndex).trim();
                String value = line.substring(eqIndex + 1).trim();
                if ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                } else {
                    int commentIndex = value.indexOf(" #");
                    if (commentIndex >= 0) value = value.substring(0, commentIndex).trim();
                }
                if (System.getProperty(key) == null && System.getenv(key) == null) {
                    System.setProperty(key, value);
                    loadedVariables++;
                }
            }
        } catch (Exception e) {
            log.warn("[警告] canonical 本地配置文件读取失败: {}", CANONICAL_ENV_FILE);
            return;
        }
        log.info("[提示] 已加载 canonical 本地配置文件（{} 个新变量）", loadedVariables);
    }
}
