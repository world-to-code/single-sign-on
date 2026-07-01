package com.example.sso.session.internal.application;

import com.example.sso.session.StepUpInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers the {@link StepUpInterceptor} for sensitive admin operations. */
@Configuration
@RequiredArgsConstructor
public class SessionWebConfig implements WebMvcConfigurer {
    private final StepUpInterceptor stepUpInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(stepUpInterceptor).addPathPatterns("/api/admin/**");
    }
}
