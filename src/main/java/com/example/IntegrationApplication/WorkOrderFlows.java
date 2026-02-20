package com.example.IntegrationApplication;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.http.dsl.Http;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.messaging.MessageChannel;

import java.util.Map;

@Configuration
public class WorkOrderFlows {

    @Value("${maintainx.api-key}")
    private String apiKey;

    // Channel that connects Flow 1 → Flow 2
    @Bean
    MessageChannel maintainxOutboundChannel() {
        return new DirectChannel();
    }

    // FLOW 1: Receive and transform
    // inbound gateway → save to DB → transformer → enrich headers → send to channel
    @Bean
    IntegrationFlow createWorkRequestFlow(WorkRequestTransformer transformer,
                                           WorkRequestRepository repo) {
        return IntegrationFlow
                .from(Http.inboundGateway("/api/workrequests")
                        .requestMapping(r -> r.methods(HttpMethod.POST)
                                .consumes(MediaType.APPLICATION_JSON_VALUE))
                        .requestPayloadType(WorkRequestPayload.class))
                // Save to DB before transforming for MaintainX
                .handle(WorkRequestPayload.class, (payload, headers) -> {
                    WorkRequest entity = repo.save(WorkRequest.builder()
                            .title(payload.title())
                            .description(payload.description())
                            .priority(payload.priority())
                            .status("PENDING_APPROVAL")
                            .build());
                    System.out.println("[DB] Saved work request with DB ID: " + entity.getId());
                    return MessageBuilder.withPayload(payload)
                            .copyHeaders(headers)
                            .setHeader("workRequestDbId", entity.getId())
                            .build();
                })
                .transform(transformer)
                .enrichHeaders(h -> h.header("Authorization", "Bearer " + apiKey))
                .channel("maintainxOutboundChannel")
                .get();
    }

    // FLOW 2: Call MaintainX API
    // picks up from channel → outbound gateway → update DB with MaintainX ID → reply back
    @Bean
    IntegrationFlow sendToMaintainxFlow(WorkRequestRepository repo) {
        return IntegrationFlow
                .from("maintainxOutboundChannel")
                .handle(Http.outboundGateway("https://api.getmaintainx.com/v1/workrequests")
                        .httpMethod(HttpMethod.POST)
                        .mappedRequestHeaders("Content-Type", "Authorization")
                        .expectedResponseType(Map.class))
                .handle(Map.class, (response, headers) -> {
                    Long maintainxId = ((Number) response.get("id")).longValue();
                    Long dbId = (Long) headers.get("workRequestDbId");

                    System.out.println("=== MAINTAINX RESPONSE ===");
                    System.out.println("  ID: " + maintainxId);

                    repo.findById(dbId).ifPresent(entity -> {
                        entity.setMaintainxId(maintainxId);
                        repo.save(entity);
                        System.out.println("[DB] Updated DB ID " + dbId + " with MaintainX ID: " + maintainxId);
                    });

                    return "Work request created — MaintainX ID: " + maintainxId;
                })
                .get();
    }

    // FLOW 3: Webhook — Status Change
    @Bean
    IntegrationFlow webhookStatusChangeFlow(WorkRequestRepository repo) {
        return IntegrationFlow
                .from(Http.inboundChannelAdapter("/webhook/maintainx/requeststatus")
                        .requestMapping(r -> r.methods(HttpMethod.POST))
                        .requestPayloadType(Map.class)
                        .statusCodeFunction(_ -> HttpStatus.OK.value()))
                .handle(Map.class, (payload, _) -> {
                    Long requestId = ((Number) payload.get("requestId")).longValue();
                    String newStatus = (String) payload.get("newStatus");

                    System.out.println("=== WEBHOOK: STATUS CHANGE ===");
                    System.out.println("  Request ID: " + requestId);
                    System.out.println("  New Status: " + newStatus);

                    repo.findByMaintainxId(requestId).ifPresentOrElse(entity -> {
                        entity.setStatus(newStatus);
                        repo.save(entity);
                        System.out.println("[DB] Updated status for MaintainX ID " + requestId + " → " + newStatus);
                    }, () -> {
                        System.out.println("[DB] No work request found for MaintainX ID: " + requestId);
                    });

                    return null;
                })
                .get();
    }
}
