package com.example.IntegrationApplication;

/**
 * What the user sends when creating a work request.
 *
 * This record is used as the `requestPayloadType` in the inbound gateway:
 *   .requestPayloadType(WorkRequestPayload.class)
 *
 * Spring + Jackson will automatically deserialize the JSON body into this record.
 * For example, this JSON:
 *   {"title": "AC broken", "description": "Office is hot", "priority": "MEDIUM"}
 * becomes:
 *   WorkRequestPayload("AC broken", "Office is hot", "MEDIUM")
 *
 * This is the same pattern as your existing TicketRequest record.
 */
public record WorkRequestPayload(
    String title,        // required — what's the issue
    String description,  // optional — more details
    String priority      // "LOW", "MEDIUM", "HIGH"
) {}