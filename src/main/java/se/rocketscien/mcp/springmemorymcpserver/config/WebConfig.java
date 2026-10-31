package se.rocketscien.mcp.springmemorymcpserver.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import se.rocketscien.mcp.springmemorymcpserver.auth.TokenAuthFilter;

@Configuration
public class WebConfig {

    @Bean
    public FilterRegistrationBean<TokenAuthFilter> tokenAuthFilterRegistration(TokenAuthFilter tokenAuthFilter) {
        var registration = new FilterRegistrationBean<>(tokenAuthFilter);
        registration.addUrlPatterns("/mcp", "/mcp/*", "/api/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                // Only the read endpoint is callable from a browser page: the write endpoint must not be.
                registry.addMapping("/api/query")
                        .allowedOrigins("*")
                        .allowedMethods("POST", "OPTIONS")
                        .allowedHeaders("Authorization", "X-Access-Token", "Content-Type");
            }
        };
    }
}
