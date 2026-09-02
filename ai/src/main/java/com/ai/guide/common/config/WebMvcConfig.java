package com.ai.guide.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import java.util.Arrays;

/**
 * Web MVC 跨域与拦截器配置
 *
 * 所属领域：common.config（基础设施配置）
 * 架构职责：配置跨域资源共享 (CORS) 允许策略与统一静态资源映射。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
    private final String[] allowedOrigins;

    public WebMvcConfig(@Value("${YUYOUZHICE_WEB_ORIGINS:}") String configuredOrigins) {
        this.allowedOrigins = Arrays.stream(String.valueOf(configuredOrigins == null ? "" : configuredOrigins).split(","))
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .toArray(String[]::new);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        CorsRegistration registration = registry.addMapping("/**")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
        // Spring MVC applies permissive defaults when allowed origins are not
        // explicitly set. Always set the parsed allowlist, including empty, so
        // an omitted YUYOUZHICE_WEB_ORIGINS never becomes wildcard CORS.
        registration.allowedOrigins(allowedOrigins);
    }
}
