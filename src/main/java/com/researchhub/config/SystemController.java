package com.researchhub.config;

import com.researchhub.HubProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SystemController {
    public record Capabilities(String generationMode, int maxUploadMb, boolean ocrSupported) { }
    private final HubProperties properties;

    public SystemController(HubProperties properties) { this.properties = properties; }

    @GetMapping("/api/system")
    public Capabilities capabilities() {
        return new Capabilities(properties.llm().mode(), 8, false);
    }
}
