package com.elderlyhealth.agent.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/static/**")
                .addResourceLocations("file:src/main/resources/static/");
        registry.addResourceHandler("/images/**")
                .addResourceLocations("file:src/main/resources/static/images/");
        registry.addResourceHandler("/files/**")
                .addResourceLocations("file:src/main/resources/static/files/");
        registry.addResourceHandler("/voices/**")
                .addResourceLocations("file:src/main/resources/static/voices/");
    }
}
