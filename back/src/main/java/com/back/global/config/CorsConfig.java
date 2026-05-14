package com.back.global.config;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
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
                .allowedOrigins(allowedOrigins())
                .allowedMethods("GET", "POST", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    private String[] allowedOrigins() {
        String origin = frontendOrigin();
        List<String> origins = new ArrayList<>();
        origins.add(origin);

        // www ↔ non-www 둘 다 허용 (NPM에 양쪽 모두 프록시될 수 있으므로)
        URI uri = URI.create(origin);
        String host = uri.getHost();
        int port = uri.getPort();
        String portPart = port == -1 ? "" : ":" + port;
        if (host.startsWith("www.")) {
            origins.add(uri.getScheme() + "://" + host.substring(4) + portPart);
        } else if (!host.equals("localhost")) {
            origins.add(uri.getScheme() + "://www." + host + portPart);
        }

        return origins.toArray(new String[0]);
    }

    private String frontendOrigin() {
        String value = frontendBaseUrl == null ? "" : frontendBaseUrl.trim();
        URI uri = URI.create(value);
        int port = uri.getPort();
        String portPart = port == -1 ? "" : ":" + port;
        return uri.getScheme() + "://" + uri.getHost() + portPart;
    }
}
