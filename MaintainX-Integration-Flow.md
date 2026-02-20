# MaintainX Integration Flow — Complete Guide

## What We Built

An app that does two things:
1. **Creates work requests** in MaintainX (outbound)
2. **Receives notifications** when something changes (inbound via webhooks)

---

## How to Think About Building an Integration Flow

Before writing any code, ask yourself these 5 questions:

### Question 1: What triggers the flow?
- Is it a **user action**? (someone submits a form, clicks a button) → You need an **inbound gateway** to receive their request
- Is it an **external system notifying you**? (MaintainX sends a webhook) → You need an **inbound adapter** to receive it
- Is it a **scheduled task**? (run every 5 minutes) → You need a **poller**

### Question 2: Does the caller need a response?
- **Yes** (user submits and waits for confirmation) → Use `Http.inboundGateway` (request-reply pattern)
- **No** (external system just notifies you) → Use `Http.inboundChannelAdapter` (fire-and-forget pattern)

### Question 3: Does the data need to be reshaped?
- Your internal model is NEVER the same as the external API's model
- You always need a **Transformer** to convert between them
- Example: User sends `{title, description, priority}` but MaintainX needs `{title, description, priority, locationId, creatorContactInfo}`

### Question 4: Do you need to call an external API?
- If yes, you need an **outbound call** (RestTemplate, WebClient, or Http.outboundGateway)
- You'll need: the URL, auth headers, request body, and to handle the response

### Question 5: Do you need to track state?
- If yes, you need a **store** (database, in-memory map, etc.)
- Multiple flows might read/write to the same store

---

## The Setup (3 pieces running together)

```
+----------------------+     +----------------------+     +----------------------+
|   Your Spring App    |     |       ngrok          |     |     MaintainX        |
|   localhost:8080     |<--->|   public tunnel      |<--->|   their servers      |
|                      |     |                      |     |                      |
| - receives your curl |     | - forwards traffic   |     | - stores work        |
| - calls MaintainX    |     |   between internet   |     |   requests           |
| - receives webhooks  |     |   and localhost       |     | - sends webhooks     |
| - stores state       |     |                      |     |   when things change |
+----------------------+     +----------------------+     +----------------------+
```

**Why 3 pieces?**
- Your app runs on `localhost:8080` — only your machine can reach it
- MaintainX lives on the internet — it can't call `localhost`
- ngrok creates a public URL that tunnels traffic to your localhost
- In production, there's no ngrok — your app runs on a real server with a real domain

---

## PART 1: Creating a Work Request (Outbound)

**The thinking**: A user wants to create a work request. They send us JSON, we reshape it for MaintainX, call their API, store the result, and reply with a confirmation.

**Flow**: User → Inbound Gateway → Transformer → Handler (API call + store) → Reply

### Step 1 — Receive the user's request

```
curl POST http://localhost:8080/api/workrequests
{"title":"AC not working", "description":"Office is hot", "priority":"MEDIUM"}
```

**Java:**

```java
Http.inboundGateway("/api/workrequests")
    .requestMapping(r -> r.methods(HttpMethod.POST)
            .consumes(MediaType.APPLICATION_JSON_VALUE))
    .requestPayloadType(WorkRequestPayload.class)
```

**Explanation:**
- `Http.inboundGateway(path)` — creates an HTTP endpoint that **waits for a reply**. The user's curl command will hang until our flow finishes and returns something. This is the "request-reply" pattern.
- `.requestMapping(r -> r.methods(POST).consumes(JSON))` — only accepts POST requests with JSON body. Anything else gets rejected with 405/415.
- `.requestPayloadType(WorkRequestPayload.class)` — tells Jackson to deserialize the JSON body into this Java record. This is the same as `@RequestBody WorkRequestPayload` in a Spring controller.

**What Spring Integration creates internally:**
```
Message {
    payload: WorkRequestPayload("AC not working", "Office is hot", "MEDIUM")
    headers: {
        replyChannel: TemporaryReplyChannel@abc123   <-- WHERE to send the reply
        errorChannel: TemporaryReplyChannel@abc123   <-- WHERE to send errors
        contentType: application/json
        http_requestMethod: POST
        Content-Length: 81
    }
}
```

**Why `replyChannel` matters:** The gateway creates a temporary channel and puts it in the headers. When the final handler returns a value, Spring Integration sends that value to `replyChannel`, which sends it back as the HTTP response. If this header gets lost (as we experienced), the reply has nowhere to go and you get an error.

**In the real codebase:** This is equivalent to `rayWebFluxGatewayFactory.createInbound(...)`.

---

### Step 2 — Transform the data

**The thinking**: MaintainX API requires fields that the user didn't send (like `locationId`, `creatorContactInfo`). We need to add them. We also want to validate the input before calling the API.

**Java — WorkRequestPayload.java (the input model):**

```java
public record WorkRequestPayload(
    String title,        // required
    String description,  // optional
    String priority      // "LOW", "MEDIUM", "HIGH"
) {}
```

**Explanation:** A Java record is a simple immutable data class. Jackson automatically maps JSON fields to record fields by name. This is the same pattern as `TicketRequest` in the existing codebase. You use a record (or class) as the `requestPayloadType` so Spring deserializes the JSON for you — no manual parsing.

**Java — WorkRequestTransformer.java:**

```java
@Component
public class WorkRequestTransformer
    implements GenericTransformer<Message<WorkRequestPayload>, Message<Map<String, Object>>> {

    @Override
    public Message<Map<String, Object>> transform(Message<WorkRequestPayload> message) {
        WorkRequestPayload request = message.getPayload();

        // Validate
        if (request.title() == null || request.title().isBlank()) {
            throw new IllegalArgumentException("title cannot be blank");
        }

        // Build MaintainX API payload (add fields they require)
        Map<String, Object> maintainxPayload = Map.of(
            "title", request.title(),
            "description", request.description() != null ? request.description() : "",
            "priority", request.priority() != null ? request.priority() : "MEDIUM",
            "locationId", 4417032,
            "creatorContactInfo", "user@ray.com"
        );

        return MessageBuilder.withPayload(maintainxPayload)
                .setHeader(MessageHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setHeader(MessageHeaders.REPLY_CHANNEL, message.getHeaders().getReplyChannel())
                .setHeader(MessageHeaders.ERROR_CHANNEL, message.getHeaders().getErrorChannel())
                .build();
    }
}
```

**Explanation line by line:**

- `@Component` — Spring creates one instance (singleton) and injects it wherever needed. The flow references it as `.transform(transformer)`.

- `GenericTransformer<Message<WorkRequestPayload>, Message<Map<String, Object>>>` — the generic types define input and output:
  - **Input**: `Message<WorkRequestPayload>` — a Message whose payload is your record
  - **Output**: `Message<Map<String, Object>>` — a Message whose payload is a Map (the MaintainX format)
  - We use `Message<>` wrapper (not just raw types) because we need access to headers

- `message.getPayload()` — extracts the actual data from the Message wrapper

- `Map.of("title", ..., "locationId", 4417032)` — builds the payload that MaintainX expects. In production, `locationId` would come from user profile/database, not hardcoded.

- `MessageBuilder.withPayload(maintainxPayload)` — creates a NEW Message with the transformed payload

- `.setHeader(REPLY_CHANNEL, ...)` and `.setHeader(ERROR_CHANNEL, ...)` — **CRITICAL**: we must preserve these headers from the original message. Without them, the reply can't get back to the user. We learned this the hard way — if you build a new Message and forget to carry these headers, the gateway can't send the HTTP response back.

- We do NOT copy ALL headers (`.copyHeaders()`) because that would include `Content-Length` from the original HTTP request, which is wrong for the new (larger) payload and causes `too many bytes written` errors.

**Before and after the transformer:**
```
BEFORE (what user sent):
  {"title":"AC not working", "description":"Office is hot", "priority":"MEDIUM"}

AFTER (what MaintainX needs):
  {"title":"AC not working", "description":"Office is hot", "priority":"MEDIUM",
   "locationId":4417032, "creatorContactInfo":"user@ray.com"}
```

**In the real codebase:** This is equivalent to `createTicketExternalReplicationModelTransformer` — it takes your internal model and converts it to the external API's expected format.

---

### Step 3 — Call MaintainX API and store the result

**The thinking**: We have the transformed payload. Now we need to: (1) call MaintainX API with auth, (2) read the response, (3) store the ID and status, (4) reply to the user.

**Java (inside the flow):**

```java
.handle(Map.class, (payload, headers) -> {
    // 1. Build HTTP request with auth headers
    HttpHeaders httpHeaders = new HttpHeaders();
    httpHeaders.setContentType(MediaType.APPLICATION_JSON);
    httpHeaders.setBearerAuth(apiKey);  // "Bearer eyJhbGci..."

    // 2. Wrap payload + headers into one object
    HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, httpHeaders);

    // 3. Call MaintainX API
    RestTemplate restTemplate = new RestTemplate();
    ResponseEntity<Map> response = restTemplate.postForEntity(
            "https://api.getmaintainx.com/v1/workrequests",
            entity, Map.class);

    // 4. Read the response
    Map body = response.getBody();
    Long maintainxId = ((Number) body.get("id")).longValue();

    // 5. Store in memory
    store.save(maintainxId, new HashMap<>(Map.of(
        "maintainxId", maintainxId,
        "title", payload.getOrDefault("title", ""),
        "status", "OPEN",
        "createdAt", java.time.Instant.now().toString()
    )));

    // 6. Reply to user (this goes back through replyChannel -> HTTP response)
    return "Request created (MaintainX ID: " + maintainxId + ") - Status: OPEN";
})
```

**Explanation line by line:**

- `.handle(Map.class, (payload, headers) -> {...})` — a handler receives the Message payload and headers. The `Map.class` tells Spring "the payload at this point is a Map". The lambda does the work and returns a value.

- `HttpHeaders` — standard Spring class for HTTP headers. `.setBearerAuth(apiKey)` adds `Authorization: Bearer <token>`. `.setContentType(JSON)` sets `Content-Type: application/json`.

- `HttpEntity<Map>` — wraps the body (our Map payload) and headers (auth + content type) into one object that RestTemplate can send.

- `RestTemplate.postForEntity(url, entity, responseType)` — sends an HTTP POST to MaintainX. The Map payload gets automatically serialized to JSON by Jackson. Returns a `ResponseEntity` with status code and body.

- `response.getBody().get("id")` — MaintainX returns `{"id": 10404919}`. We extract the ID.

- `store.save(...)` — saves the request to our in-memory store so we can track its status when webhooks arrive.

- `return "Request created..."` — this string becomes the HTTP response that the user sees. It flows back through the `replyChannel` header to the inbound gateway, which sends it as the HTTP 200 response body.

**The HTTP exchange that happens:**
```
YOUR APP --> MAINTAINX:
  POST https://api.getmaintainx.com/v1/workrequests
  Authorization: Bearer eyJhbGci...
  Content-Type: application/json
  {"title":"AC not working","locationId":4417032,"creatorContactInfo":"user@ray.com",...}

MAINTAINX --> YOUR APP:
  200 OK
  {"id": 10404919}
```

**Why RestTemplate instead of Http.outboundGateway?**
Spring Integration's `Http.outboundGateway()` creates a NEW message from the HTTP response, which loses the `replyChannel` header. Using `RestTemplate` directly inside the handler keeps everything in one step — the `replyChannel` stays intact because no intermediate component replaces the message.

In the real codebase, `WebFlux.outboundGateway(url, webClient)` is used instead. It works because the real codebase handles the reply channel differently (through named channels and routers). For this learning project, RestTemplate inside the handler is the simplest working approach.

**In the real codebase:** This is equivalent to `WebFlux.outboundGateway(url, webClient).httpMethod(POST)` — calling the external MaintainX API.

---

### Step 4 — Store for shared state

**The thinking**: We need to remember what work requests we created and what their current status is. When a webhook arrives later (minutes, hours, days), we need to look up the request and update it.

**Java — WorkOrderStore.java:**

```java
@Component
public class WorkOrderStore {

    private final ConcurrentHashMap<Long, Map<String, Object>> store = new ConcurrentHashMap<>();

    public void save(Long maintainxId, Map<String, Object> request) {
        store.put(maintainxId, new ConcurrentHashMap<>(request));
    }

    public Map<String, Object> get(Long maintainxId) {
        return store.get(maintainxId);
    }

    public Map<Long, Map<String, Object>> getAll() {
        return Map.copyOf(store);
    }
}
```

**Explanation:**

- `@Component` — Spring singleton. Every flow that injects `WorkOrderStore` gets the **same instance**. This is how the outbound flow (saves request) and webhook flow (updates status) share data.

- `ConcurrentHashMap<Long, Map<String, Object>>` — thread-safe Map. The key is the MaintainX ID (`Long`), the value is a Map of field names to values. We use `ConcurrentHashMap` because the outbound flow and webhook flow can run on different threads simultaneously.

- **What's stored:**
```
store = {
    10404919 -> {
        "maintainxId": 10404919,
        "title": "AC not working",
        "status": "OPEN",            <-- changes when webhooks arrive
        "createdAt": "2026-02-17..."
    }
}
```

**In the real codebase:** This would be a database (PostgreSQL, etc.) with a proper entity/repository. The ConcurrentHashMap serves the same purpose for learning — it's shared state between flows.

---

## PART 2: Receiving Webhooks (Inbound)

**The thinking**: When someone approves/rejects/completes a work request in MaintainX, they send us a notification (webhook). We need to: (1) receive it, (2) return 200 OK immediately, (3) update our stored state.

### How the webhook reaches your app

```
1. Someone clicks "Approve" in MaintainX dashboard

2. MaintainX server sends HTTP POST to the webhook URL you configured:
   POST https://nonobservable-ema-nonalternating.ngrok-free.dev/webhook/maintainx/requeststatus
   {"id":10404919, "status":"APPROVED", ...}

3. ngrok receives this on the internet and forwards it to your machine:
   POST http://localhost:8080/webhook/maintainx/requeststatus
   {"id":10404919, "status":"APPROVED", ...}

4. Your Spring app receives it as a normal HTTP POST on port 8080
   (your code never knows ngrok exists)
```

**ngrok is not in the code.** It runs separately (`ngrok http 8080`). It just forwards traffic. In production, your app has a real domain and MaintainX calls it directly.

### The webhook receiver flows

**Java — New Work Request webhook:**

```java
@Bean
IntegrationFlow webhookNewWorkRequestFlow(WorkOrderStore store) {
    return IntegrationFlow
            .from(Http.inboundChannelAdapter("/webhook/maintainx/workrequest")
                    .requestMapping(r -> r.methods(HttpMethod.POST))
                    .requestPayloadType(Map.class)
                    .statusCodeFunction(_ -> HttpStatus.OK.value()))
            .log(LoggingHandler.Level.INFO, "Webhook.NewWorkRequest")
            .handle(Map.class, (payload, _) -> {
                System.out.println("=== WEBHOOK: NEW WORK REQUEST ===");
                System.out.println("  Payload: " + payload);
                return null;
            })
            .get();
}
```

**Java — Status Change webhook:**

```java
@Bean
IntegrationFlow webhookStatusChangeFlow(WorkOrderStore store) {
    return IntegrationFlow
            .from(Http.inboundChannelAdapter("/webhook/maintainx/requeststatus")
                    .requestMapping(r -> r.methods(HttpMethod.POST))
                    .requestPayloadType(Map.class)
                    .statusCodeFunction(_ -> HttpStatus.OK.value()))
            .log(LoggingHandler.Level.INFO, "Webhook.StatusChange")
            .handle(Map.class, (payload, _) -> {
                Long requestId = payload.containsKey("id")
                        ? ((Number) payload.get("id")).longValue()
                        : null;
                String status = (String) payload.get("status");

                if (requestId != null) {
                    Map<String, Object> existing = store.get(requestId);
                    if (existing != null) {
                        String oldStatus = (String) existing.get("status");
                        var updated = new HashMap<>(existing);
                        updated.put("status", status);
                        updated.put("updatedAt", java.time.Instant.now().toString());
                        store.save(requestId, updated);
                        System.out.println("  >> Updated: " + oldStatus + " -> " + status);
                    }
                }
                return null;  // fire-and-forget
            })
            .get();
}
```

**Explanation line by line:**

- `Http.inboundChannelAdapter(path)` — **NOT a gateway**. This is the fire-and-forget pattern. It returns 200 OK immediately without waiting for the handler to finish. MaintainX just needs to know we received the webhook — it doesn't care about a response body.

- `.requestPayloadType(Map.class)` — we use `Map` (not a typed record) because we don't control what MaintainX sends. Their payload structure might change, and we want to be flexible. We log the full payload first to see what they actually send.

- `.statusCodeFunction(_ -> HttpStatus.OK.value())` — always return 200. If we returned an error, MaintainX would retry the webhook.

- `return null` — no reply needed. The adapter already returned 200. The handler just processes the data.

- `store.get(requestId)` → `store.save(requestId, updated)` — find the existing request in our store and update its status. This is the same store that the outbound flow wrote to in Part 1.

**Why two separate endpoints?**
You configured two webhooks in MaintainX:
- "New Work Request" → sends to `/webhook/maintainx/workrequest`
- "Work Request Status Change" → sends to `/webhook/maintainx/requeststatus`

Each event type goes to a different URL, so each gets its own flow. This makes it easier to handle different payload structures and logic per event type.

**Gateway vs Adapter — when to use which:**

| | Gateway | Adapter |
|---|---|---|
| **Pattern** | Request-reply | Fire-and-forget |
| **Caller** | Waits for response | Gets 200 immediately |
| **Java** | `Http.inboundGateway(...)` | `Http.inboundChannelAdapter(...)` |
| **Handler returns** | A value (sent as HTTP response) | `null` (no reply needed) |
| **Use for** | User-facing APIs, queries | Webhooks, notifications |
| **replyChannel** | Required (must be preserved!) | Not used |

---

## PART 3: Checking State (Query)

**The thinking**: We want to see all stored requests and their current status.

```java
@Bean
IntegrationFlow queryWorkRequestsFlow(WorkOrderStore store) {
    return IntegrationFlow
            .from(Http.inboundGateway("/api/workrequests")
                    .requestMapping(r -> r.methods(HttpMethod.GET))
                    .requestPayloadType(String.class))
            .handle((_, _) -> store.getAll())
            .get();
}
```

**Explanation:** Simple gateway — receives GET, returns the entire store contents as JSON. Jackson automatically serializes the Map to JSON.

---

## Configuration

**application.properties:**
```properties
spring.application.name=IntegrationApplication
maintainx.api-key=${MAINTAINX_API_KEY}
```

**Explanation:** `${MAINTAINX_API_KEY}` reads from an environment variable. You set this in your IntelliJ run configuration or `.env` file. The `@Value("${maintainx.api-key}")` annotation in `WorkOrderFlows.java` injects it.

---

## The Complete File Structure

```
src/main/java/com/example/IntegrationApplication/
|
|-- WorkRequestPayload.java        Input model (Java record)
|                                   What: defines the JSON structure users send
|                                   Why: Jackson deserializes JSON into this
|                                   Pattern: same as TicketRequest
|
|-- WorkRequestTransformer.java    Transformer
|                                   What: converts user model -> MaintainX model
|                                   Why: MaintainX needs extra fields (locationId, etc.)
|                                   Pattern: GenericTransformer<Message<A>, Message<B>>
|                                   Same as: TicketDetailTransformer
|
|-- WorkOrderStore.java            Shared state
|                                   What: ConcurrentHashMap holding requests + status
|                                   Why: both outbound and webhook flows need shared data
|                                   Pattern: @Component singleton
|                                   Same as: database/repository in production
|
|-- WorkOrderFlows.java            All flows
|                                   What: @Configuration with all IntegrationFlow beans
|                                   Contains:
|                                     - createWorkRequestFlow (outbound, gateway)
|                                     - webhookNewWorkRequestFlow (inbound, adapter)
|                                     - webhookStatusChangeFlow (inbound, adapter)
|                                     - queryWorkRequestsFlow (query, gateway)
```

---

## How to Think When Implementing in the Real Codebase

### Step-by-step approach:

**1. Identify the trigger**
- User action? → inbound gateway
- External webhook? → inbound adapter
- Scheduled? → poller

**2. Define the input model**
- What JSON does the caller send?
- Create a record/class for it
- Use it as `requestPayloadType`

**3. Build the transformer**
- What does the external API expect?
- What fields do you need to add/remove/rename?
- Implement `GenericTransformer<Message<YourModel>, Message<ApiModel>>`
- Always preserve `replyChannel` and `errorChannel` if you're in a gateway flow

**4. Make the external call**
- What URL? What HTTP method? What auth?
- Use RestTemplate/WebClient in a handler, or `Http.outboundGateway` / `WebFlux.outboundGateway`
- Handle the response (extract ID, status, etc.)

**5. Store/persist the result**
- What do you need to remember?
- Save to database/store
- This state will be needed when webhooks arrive

**6. Set up webhook receivers**
- What events does the external system send?
- Create one adapter per event type
- Use `Map.class` as payload type (you don't control external payloads)
- Log the full payload first to understand the structure
- Return 200 immediately (fire-and-forget)
- Update your store based on the webhook data

**7. Test the full lifecycle**
- Create something via your outbound flow
- Check it was stored correctly
- Trigger a webhook (manually or via the external system)
- Verify the status was updated

---

## How This Maps to the Real Codebase

| This Project | Real Codebase | Pattern |
|---|---|---|
| `WorkRequestPayload` | `FormSubmissionLegacyModel` | Input model (record/class) |
| `WorkRequestTransformer` | `createTicketExternalReplicationModelTransformer` | `GenericTransformer` — reshapes data |
| `RestTemplate.postForEntity(url)` | `WebFlux.outboundGateway(url, webClient)` | Call external API |
| `Http.inboundGateway("/api/...")` | `rayWebFluxGatewayFactory.createInbound(...)` | Receive HTTP, reply |
| `Http.inboundChannelAdapter("/webhook/...")` | Webhook receiver flows | Receive HTTP, fire-and-forget |
| `WorkOrderStore` (HashMap) | Database / repository | Shared state |
| `@Value("${maintainx.api-key}")` | Environment/vault config | Externalize secrets |
| ngrok | Real server with domain | Expose app to internet |

---

## Testing Commands

```bash
# Start ngrok (separate terminal)
ngrok http 8080

# Start the app
./mvnw spring-boot:run

# 1. Create a work request (outbound flow)
curl -X POST http://localhost:8080/api/workrequests \
  -H "Content-Type: application/json" \
  -d '{"title":"AC not working","description":"Office is very hot","priority":"MEDIUM"}'
# Expected: "Request created (MaintainX ID: 10404919) - Status: OPEN"

# 2. Check stored state (query flow)
curl http://localhost:8080/api/workrequests
# Expected: {10404919: {title:"AC not working", status:"OPEN"}}

# 3. Test webhook manually (simulates what MaintainX sends)
curl -X POST http://localhost:8080/webhook/maintainx/requeststatus \
  -H "Content-Type: application/json" \
  -d '{"id":10404919,"status":"APPROVED"}'
# Expected: 200 OK (no body, fire-and-forget)

# 4. Check updated state
curl http://localhost:8080/api/workrequests
# Expected: {10404919: {title:"AC not working", status:"APPROVED"}}
```

---

## Lessons Learned (Common Pitfalls)

1. **replyChannel gets lost** — When you create a new Message in a transformer or when `Http.outboundGateway` creates a response message, the `replyChannel` header can get lost. Always preserve it explicitly with `.setHeader(MessageHeaders.REPLY_CHANNEL, ...)`.

2. **Content-Length header mismatch** — If you copy ALL headers from the inbound request to the outbound request, the original `Content-Length` (e.g. 81 bytes) doesn't match the transformed payload (e.g. 150 bytes). Never blindly `.copyHeaders()` — only copy what you need.

3. **Map payload defaults to multipart** — If a Message has a `Map` payload but no `Content-Type` header, Spring defaults to `multipart/form-data`. Always set `Content-Type: application/json` explicitly when the payload is a Map that should be sent as JSON.

4. **Gateway vs Adapter confusion** — Use gateway when the caller needs a reply. Use adapter when they don't. Using a gateway for webhooks means MaintainX waits for your processing to finish before getting a 200. Using an adapter for user APIs means the user never gets a response.