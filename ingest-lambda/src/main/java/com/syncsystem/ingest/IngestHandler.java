package com.syncsystem.ingest;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class IngestHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final Map<String, String> API_KEY_TO_VENDOR = Map.of(
            "test-key", "vnd_test_001"
    );

    private final S3Client s3 = S3Client.builder().build();
    private final DynamoDbClient ddb = DynamoDbClient.builder().build();
    private final EventBridgeClient eb = EventBridgeClient.builder().build();

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        String requestId = context != null && context.getAwsRequestId() != null ? context.getAwsRequestId() : "unknown";
        String path = request.getPath();
        if (path == null) path = "";

        String method = httpMethod(request);

        if ("GET".equalsIgnoreCase(method) && path.contains("/admin/ingest/")) {
            return handleGetIngest(request, context);
        }

        try {
            // 1) Auth
            String apiKey = header(request, "X-Api-Key");
            if (apiKey == null || apiKey.isBlank()) {
                return error(401, "MISSING_API_KEY", "X-Api-Key header is required.", requestId);
            }

            String vendorId = API_KEY_TO_VENDOR.get(apiKey);
            if (vendorId == null) {
                return error(401, "INVALID_API_KEY", "API key is invalid or revoked.", requestId);
            }

            // 2) Basic body validation
            String body = request.getBody();
            if (body == null || body.isBlank()) {
                return error(400, "INVALID_JSON", "Request body is required.", requestId);
            }

            String externalEventId = extractJsonString(body, "externalEventId");
            if (externalEventId == null || externalEventId.isBlank()) {
                return error(400, "MISSING_REQUIRED_FIELD", "externalEventId is required.", requestId);
            }

            // Forbid vendorId in body (per contract)
            String vendorIdInBody = extractJsonString(body, "vendorId");
            if (vendorIdInBody != null) {
                return error(400, "VENDOR_ID_NOT_ALLOWED", "vendorId must not be provided in the request body.", requestId);
            }

            // Route-specific required fields
            boolean isInventory = path.endsWith("/inventory-updates");
            boolean isOrderStatus = path.endsWith("/order-status-updates");

            if (!isInventory && !isOrderStatus) {
                return error(404, "UNKNOWN_ROUTE", "Unknown route: " + path, requestId);
            }

            if (isInventory) {
                String vendorProductKey = extractJsonString(body, "vendorProductKey");
                if (vendorProductKey == null || vendorProductKey.isBlank()) {
                    return error(400, "MISSING_REQUIRED_FIELD", "vendorProductKey is required.", requestId);
                }

                String unit = extractJsonString(body, "unit");
                if (unit == null || unit.isBlank()) {
                    return error(400, "MISSING_REQUIRED_FIELD", "unit is required.", requestId);
                }

                String semantics = extractJsonString(body, "semantics");
                if (semantics == null || semantics.isBlank()) {
                    return error(400, "MISSING_REQUIRED_FIELD", "semantics is required.", requestId);
                }

            } else { // order-status-updates
                String vendorOrderKey = extractJsonString(body, "vendorOrderKey");
                if (vendorOrderKey == null || vendorOrderKey.isBlank()) {
                    return error(400, "MISSING_REQUIRED_FIELD", "vendorOrderKey is required.", requestId);
                }

                String status = extractJsonString(body, "status");
                if (status == null || status.isBlank()) {
                    return error(400, "MISSING_REQUIRED_FIELD", "status is required.", requestId);
                }
            }

            // 3) Create ingest identifiers
            String ingestId = "ing_" + UUID.randomUUID();
            String receivedAt = Instant.now().toString();

            // 4) Resolve env
            String bucket = requireEnv("RAW_BUCKET");
            String table = requireEnv("INGEST_TABLE");

            // 5) Write raw payload to S3
            String s3Key = "raw/vendorId=" + vendorId + "/receivedAt=" + receivedAt + "/" + ingestId + ".json";

            PutObjectRequest putObj = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(s3Key)
                    .contentType("application/json")
                    .metadata(Map.of(
                            "vendorId", vendorId,
                            "externalEventId", externalEventId,
                            "receivedAt", receivedAt,
                            "ingestId", ingestId
                    ))
                    .build();

            s3.putObject(putObj, RequestBody.fromBytes(body.getBytes(StandardCharsets.UTF_8)));

            // 6) Write ingest record to DynamoDB (idempotent by vendorId + externalEventId)
            Map<String, AttributeValue> item = new HashMap<>();
            item.put("vendorId", AttributeValue.builder().s(vendorId).build());
            item.put("externalEventId", AttributeValue.builder().s(externalEventId).build());
            item.put("ingestId", AttributeValue.builder().s(ingestId).build());
            item.put("receivedAt", AttributeValue.builder().s(receivedAt).build());
            item.put("s3Bucket", AttributeValue.builder().s(bucket).build());
            item.put("s3Key", AttributeValue.builder().s(s3Key).build());
            item.put("status", AttributeValue.builder().s("INGESTED").build());

        try {
            ddb.putItem(PutItemRequest.builder()
                    .tableName(table)
                    .item(item)
                    .conditionExpression("attribute_not_exists(vendorId) AND attribute_not_exists(externalEventId)")
                    .build());

            if (context != null && context.getLogger() != null) {
                context.getLogger().log("EMITTING ingest.accepted to EventBridge\n");
            }

            PutEventsRequestEntry entry = PutEventsRequestEntry.builder()
                .eventBusName("dvss-bus")
                .source("dvss.ingest")
                .detailType("ingest.accepted")
                .detail("""
                {
                "vendorId": "%s",
                "externalEventId": "%s",
                "ingestId": "%s",
                "receivedAt": "%s",
                "s3Bucket": "%s",
                "s3Key": "%s"
                }
                """.formatted(vendorId, externalEventId, ingestId, receivedAt, bucket, s3Key))
                .build();

            eb.putEvents(PutEventsRequest.builder()
                    .entries(entry)
                    .build());
            var resp = eb.putEvents(PutEventsRequest.builder().entries(entry).build());

            if (context != null && context.getLogger() != null) {
                context.getLogger().log("PutEvents failedEntryCount=" + resp.failedEntryCount() + "\n");
                if (resp.failedEntryCount() != null && resp.failedEntryCount() > 0) {
                    context.getLogger().log("PutEvents entries=" + resp.entries() + "\n");
                }
            }


            if (context != null && context.getLogger() != null) {
                context.getLogger().log("EMIT SUCCESS\n");
            }

            return jsonResponse(202, Map.of(
                    "status", "ACCEPTED",
                    "ingestId", ingestId
            ));

        } catch (ConditionalCheckFailedException dup) {
            return jsonResponse(202, Map.of(
                    "status", "DUPLICATE",
                    "ingestId", ingestId
            ));
        }

        } catch (IllegalStateException env) {
            return error(500, "MISSING_ENV", env.getMessage(), requestId);
        } catch (Exception ex) {
            if (context != null && context.getLogger() != null) {
            context.getLogger().log("Unhandled error: " + ex + "\n");
            }
            return error(500, "INTERNAL_ERROR", "Unexpected server error.", requestId);
        }
    }

    private static String httpMethod(APIGatewayProxyRequestEvent request) {
        String m = request.getHttpMethod();
        if (m != null && !m.isBlank()) return m;

        // HTTP API v2 can omit getHttpMethod(); sometimes it's in requestContext.http.method
        if (request.getRequestContext() != null
                && request.getRequestContext().getHttpMethod() != null
                && !request.getRequestContext().getHttpMethod().isBlank()) {
            return request.getRequestContext().getHttpMethod();
        }

        return "";
    }

    private APIGatewayProxyResponseEvent handleGetIngest(APIGatewayProxyRequestEvent request, Context context) {
        String requestId = context != null && context.getAwsRequestId() != null ? context.getAwsRequestId() : "unknown";

        // 1) Auth (same as POST)
        String apiKey = header(request, "X-Api-Key");
        if (apiKey == null || apiKey.isBlank()) {
            return error(401, "MISSING_API_KEY", "X-Api-Key header is required.", requestId);
        }

        String callerVendorId = API_KEY_TO_VENDOR.get(apiKey);
        if (callerVendorId == null) {
            return error(401, "INVALID_API_KEY", "API key is invalid or revoked.", requestId);
        }

        // 2) Parse path: /dev/admin/ingest/{vendorId}/{externalEventId}
        String path = request.getPath();

        if (path == null) path = "";

        String[] parts = path.split("/");
        int base = path.startsWith("/dev/") ? 4 : 3;

        if (parts.length <= base + 1) {
            return error(400, "BAD_PATH", "Expected /admin/ingest/{vendorId}/{externalEventId}", requestId);
        }

        String vendorIdFromPath = parts[base];
        String externalEventId = parts[base + 1];

        // 3) Security: vendor can only read their own record
        if (!vendorIdFromPath.equals(callerVendorId)) {
            return error(403, "FORBIDDEN", "Cannot access ingest records for another vendor.", requestId);
        }

        // 4) Read from DynamoDB
        String table = requireEnv("INGEST_TABLE");

        Map<String, AttributeValue> key = new HashMap<>();
        key.put("vendorId", AttributeValue.builder().s(vendorIdFromPath).build());
        key.put("externalEventId", AttributeValue.builder().s(externalEventId).build());

        GetItemResponse resp = ddb.getItem(GetItemRequest.builder()
                .tableName(table)
                .key(key)
                .consistentRead(true)
                .build());

        if (resp.item() == null || resp.item().isEmpty()) {
            return error(404, "NOT_FOUND", "No ingest record found.", requestId);
        }

        Map<String, AttributeValue> item = resp.item();

        // 5) Return small subset
        String ingestId = item.get("ingestId") != null ? item.get("ingestId").s() : "";
        String receivedAt = item.get("receivedAt") != null ? item.get("receivedAt").s() : "";
        String s3Bucket = item.get("s3Bucket") != null ? item.get("s3Bucket").s() : "";
        String s3Key = item.get("s3Key") != null ? item.get("s3Key").s() : "";
        String status = item.get("status") != null ? item.get("status").s() : "";

        return jsonResponse(200, Map.of(
                "vendorId", vendorIdFromPath,
                "externalEventId", externalEventId,
                "ingestId", ingestId,
                "receivedAt", receivedAt,
                "status", status,
                "s3Bucket", s3Bucket,
                "s3Key", s3Key
        ));
    }

    private static String requireEnv(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Missing required environment variable: " + name);
        }
        return v;
    }

    private static String header(APIGatewayProxyRequestEvent req, String key) {
        if (req.getHeaders() == null) return null;

        // Headers can come in various casing from API Gateway
        for (Map.Entry<String, String> e : req.getHeaders().entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)) {
                return e.getValue();
            }
        }
        return null;
    }

    // Minimal JSON string extractor (MVP only). Replace with Jackson next.
    private static String extractJsonString(String json, String field) {
        String needle = "\"" + field + "\"";
        int i = json.indexOf(needle);
        if (i < 0) return null;
        int colon = json.indexOf(":", i + needle.length());
        if (colon < 0) return null;
        int firstQuote = json.indexOf("\"", colon);
        if (firstQuote < 0) return null;
        int secondQuote = json.indexOf("\"", firstQuote + 1);
        if (secondQuote < 0) return null;
        return json.substring(firstQuote + 1, secondQuote);
    }

    private static APIGatewayProxyResponseEvent error(int status, String code, String message, String requestId) {
        String body = "{\"error\":\"" + code + "\",\"message\":\"" + escape(message) + "\",\"requestId\":\"" + escape(requestId) + "\"}";
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(status)
                .withHeaders(Map.of("Content-Type", "application/json"))
                .withBody(body);
    }

    private static APIGatewayProxyResponseEvent jsonResponse(int status, Map<String, String> kv) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        boolean first = true;
        for (Map.Entry<String, String> e : kv.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escape(e.getKey())).append("\":");
            sb.append("\"").append(escape(e.getValue())).append("\"");
        }
        sb.append("}");
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(status)
                .withHeaders(Map.of("Content-Type", "application/json"))
                .withBody(sb.toString());
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
