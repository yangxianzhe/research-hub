package com.researchhub;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "hub")
public record HubProperties(@Valid Jobs jobs, @Valid Llm llm) {
    public record Jobs(@Min(1) @Max(8) int concurrency, @Min(1) @Max(10) int maxAttempts,
                       @Min(30) int leaseSeconds, @Min(100) long pollMs) { }

    public record Llm(@Pattern(regexp = "local|openai") String mode, String baseUrl, String apiKey,
                      String model, @Min(1) @Max(90) int timeoutSeconds) { }
}
