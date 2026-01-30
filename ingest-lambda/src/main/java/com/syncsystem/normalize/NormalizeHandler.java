package com.syncsystem.normalize;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.nio.charset.StandardCharsets;
import java.util.Map;

public class NormalizeHandler implements RequestHandler<Map<String, Object>, Void> {

    private final S3Client s3 = S3Client.builder().build();

    @Override
    @SuppressWarnings("unchecked")
    public Void handleRequest(Map<String, Object> event, Context context) {

        context.getLogger().log("dvss-normalize invoked\n");
        context.getLogger().log("event=" + event + "\n");

        Map<String, Object> detailMap = (Map<String, Object>) event.get("detail");
        if (detailMap == null) {
            context.getLogger().log("ERROR: Missing 'detail' in event\n");
            return null;
        }

        String s3Bucket = (String) detailMap.get("s3Bucket");
        String s3Key = (String) detailMap.get("s3Key");

        if (s3Bucket == null || s3Key == null) {
            context.getLogger().log("ERROR: Missing s3Bucket/s3Key in detail: " + detailMap + "\n");
            return null;
        }

        GetObjectRequest req = GetObjectRequest.builder()
                .bucket(s3Bucket)
                .key(s3Key)
                .build();

        try (ResponseInputStream<GetObjectResponse> obj = s3.getObject(req)) {
            String rawJson = new String(obj.readAllBytes(), StandardCharsets.UTF_8);
            context.getLogger().log("RAW_PAYLOAD=" + rawJson + "\n");
            context.getLogger().log("S3_GET_OBJECT_SUCCESS\n");
        } catch (Exception e) {
            context.getLogger().log("S3_GET_OBJECT_FAILED: " + e + "\n");
        }

        return null;
    }
}
