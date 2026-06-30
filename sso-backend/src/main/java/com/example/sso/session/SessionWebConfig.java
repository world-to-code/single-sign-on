package com.example.sso.session;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers the {@link StepUpInterceptor} for sensitive admin operations. */
@Configuration
public class SessionWebConfig implements WebMvcConfigurer {

    private final StepUpInterceptor stepUpInterceptor;

    public SessionWebConfig(StepUpInterceptor stepUpInterceptor) {
        this.stepUpInterceptor = stepUpInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(stepUpInterceptor).addPathPatterns("/api/admin/**");
    }
}
