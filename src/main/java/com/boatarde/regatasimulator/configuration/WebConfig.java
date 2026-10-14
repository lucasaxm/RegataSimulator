package com.boatarde.regatasimulator.configuration;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private static final String EDITOR_VIEW = "forward:/create/editor/index.html";

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
        registry.addViewController("/create").setViewName(EDITOR_VIEW);
        registry.addViewController("/create/").setViewName(EDITOR_VIEW);
        registry.addViewController("/create/editor").setViewName(EDITOR_VIEW);
        registry.addViewController("/create/editor/").setViewName(EDITOR_VIEW);
        registry.addViewController("/create/legacy").setViewName("forward:/create/index.html");
        registry.addViewController("/create/legacy/").setViewName("forward:/create/index.html");
    }
}
