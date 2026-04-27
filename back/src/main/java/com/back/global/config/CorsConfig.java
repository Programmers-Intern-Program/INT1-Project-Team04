package com.back.global.config;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final String frontendBaseUrl;

    public CorsConfig(@Value("${app.frontend.base-url:http://localhost:3000}") String frontendBaseUrl) {
        this.frontendBaseUrl = frontendBaseUrl;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(frontendOrigin())
                .allowedMethods("GET", "POST", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    private String frontendOrigin() {
        String value = frontendBaseUrl == null ? "" : frontendBaseUrl.trim();
        URI uri = URI.create(value);
        int port = uri.getPort();
        String portPart = port == -1 ? "" : ":" + port;
        return uri.getScheme() + "://" + uri.getHost() + portPart;
    }
}
