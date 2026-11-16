package com.researchhub.config;

import com.researchhub.auth.AuthInterceptor;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final AuthInterceptor auth;

    public WebConfig(AuthInterceptor auth) { this.auth = auth; }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(auth).addPathPatterns("/api/**", "/actuator/prometheus")
                .excludePathPatterns("/api/auth/register", "/api/auth/login");
    }

    @Bean
    OpenAPI openAPI() {
        return new OpenAPI().info(new Info().title("Research Hub API").version("0.1.0")
                        .description("课题组资料、阅读记录、组会草稿与待办。先注册，再把返回的 token 填入 Authorize。"))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
