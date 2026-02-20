package com.example.IntegrationApplication;

import org.springframework.integration.core.GenericTransformer;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class WorkRequestTransformer
        implements GenericTransformer<WorkRequestPayload, Map<String, Object>> {

    @Override
    public Map<String, Object> transform(WorkRequestPayload request) {

        if (request.title() == null || request.title().isBlank()) {
            throw new IllegalArgumentException("title cannot be blank");
        }

        System.out.println("[TRANSFORMER] Converting to MaintainX format:");
        System.out.println("  Title: " + request.title());
        System.out.println("  Priority: " + request.priority());

        return Map.of(
            "title", request.title(),
            "description", request.description() != null ? request.description() : "",
            "priority", request.priority() != null ? request.priority() : "MEDIUM"
        );
    }
}
