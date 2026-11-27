package com.fpt.autodata.generator.impl.opportunity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.autodata.config.AutodataProperties;
import com.fpt.autodata.generator.GenerationResult;
import com.fpt.autodata.generator.Generator;
import com.fpt.autodata.generator.GeneratorException;
import com.fpt.autodata.generator.ParamSpec;
import com.fpt.autodata.generator.Params;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Đánh giá cơ hội (TPP step):
 * GET /evaluation-opportunities/criteria?opportunityId=X&isFromConfig=true
 * → PUT /evaluation-opportunities/evaluate (all criteria = Đạt).
 *
 * isFromConfig=true bắt buộc từ 2026-10 — BMS frontend luôn gửi param này,
 * thiếu → 403 Forbidden. Confirmed bằng cách đọc BMS JS bundle 2026-10-27.
 *
 * Dùng cùng bearerToken (TPP) cho cả GET criteria và PUT evaluate — giống
 * flow BMS UI (TPP đăng nhập → xem criteria → submit đánh giá).
 */
@Component
public class EvaluateOpportunityGenerator implements Generator {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int PASS = 1;

    private final AutodataProperties properties;

    public EvaluateOpportunityGenerator(AutodataProperties properties) {
        this.properties = properties;
    }

    @Override public String type()        { return "evaluation.evaluate"; }
    @Override public String displayName() { return "Đánh giá cơ hội (TPP)"; }
    @Override public String category()    { return "Opportunity"; }
    @Override public String description() {
        return "GET criteria (?isFromConfig=true) rồi PUT evaluate — tất cả tiêu chí = Đạt.";
    }
    @Override public String signature() {
        return "GET {api.baseUrl}/evaluation-opportunities/criteria?isFromConfig=true -> PUT .../evaluate";
    }

    @Override
    public List<ParamSpec> params() {
        return List.of(
                ParamSpec.password("bearerToken").label("Bearer token (TPP)").required()
                        .hint("token của tài khoản TPP đánh giá — dùng cho cả GET criteria và PUT evaluate"),
                ParamSpec.text("baseUrl").label("API base URL")
                        .defaultValue(properties.getApi().getBaseUrl())
                        .hint("override chỉ khi đổi môi trường"),
                ParamSpec.text("opportunityId").label("Mã cơ hội").required()
                        .placeholder("CH00085280"),
                ParamSpec.text("note").label("Ghi chú đánh giá")
                        .defaultValue("Đánh giá tự động qua tool")
        );
    }

    @Override
    public GenerationResult generate(Map<String, Object> p) {
        String bearerToken   = Params.requireString(p, "bearerToken");
        String baseUrl       = Params.optString(p, "baseUrl", properties.getApi().getBaseUrl()).replaceAll("/$", "");
        String opportunityId = Params.requireString(p, "opportunityId");
        String note          = Params.optString(p, "note", "Đánh giá tự động qua tool");

        long t0 = System.currentTimeMillis();
        List<String> log = new ArrayList<>();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getApi().getTimeoutSeconds()))
                .build();

        // Step 1: GET criteria — isFromConfig=true bắt buộc (BMS enforce từ 2026-10)
        String criteriaUrl = baseUrl + "/evaluation-opportunities/criteria?opportunityId="
                + URLEncoder.encode(opportunityId, StandardCharsets.UTF_8)
                + "&isFromConfig=true";

        JsonNode criteriaResponse = null;
        int maxRetry = 3;
        for (int attempt = 1; attempt <= maxRetry; attempt++) {
            if (attempt > 1) {
                trace(log, "criteria chưa sẵn sàng, chờ 3s thử lại (" + attempt + "/" + maxRetry + ")…");
                try { Thread.sleep(3000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
            trace(log, "GET " + criteriaUrl);
            criteriaResponse = send(client, "GET", criteriaUrl, bearerToken, null, log);
            JsonNode ev = criteriaResponse.path("data").path("evaluations");
            if (ev.isArray() && !ev.isEmpty()) break;
            trace(log, "criteria rỗng (attempt " + attempt + ") — thử lại…");
            criteriaResponse = null;
        }

        if (criteriaResponse == null) {
            throw new GeneratorException(
                    "Không lấy được criteria sau " + maxRetry + " lần — opportunity chưa tồn tại hoặc đã đánh giá rồi?");
        }

        JsonNode evaluations = criteriaResponse.path("data").path("evaluations");
        List<Map<String, Object>> criteria = new ArrayList<>();
        for (JsonNode row : evaluations) {
            Map<String, Object> c = new LinkedHashMap<>();
            // GET response's own `id` → criteriaId cho PUT (không dùng `criteriaId` field luôn là 0)
            c.put("criteriaId", row.path("id").asLong());
            c.put("id", 0);
            c.put("criteria",    row.path("criteria").asText(null));
            c.put("requirement", row.path("requirement").asText(null));
            c.put("description", row.path("description").asText(null));
            c.put("evaluation",  PASS);
            c.put("note",        null);
            criteria.add(c);
        }

        // Step 2: PUT evaluate
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("opportunityId", opportunityId);
        payload.put("note", note);
        payload.put("criteria", criteria);

        String evaluateUrl = baseUrl + "/evaluation-opportunities/evaluate";
        trace(log, "PUT " + evaluateUrl + " (" + criteria.size() + " criteria, all Đạt)");
        JsonNode evaluateResponse = send(client, "PUT", evaluateUrl, bearerToken, toJson(payload), log);

        return result()
                .put("opportunityId", opportunityId)
                .put("criteriaCount", criteria.size())
                .put("responseBody", evaluateResponse)
                .log(log)
                .durationMs(System.currentTimeMillis() - t0)
                .build();
    }

    private JsonNode send(HttpClient client, String method, String url, String token, String body, List<String> log) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(properties.getApi().getTimeoutSeconds()))
                .header("Accept", "application/json, text/plain, */*")
                .header("Authorization", "Bearer " + token);
        if (body != null) {
            builder.header("Content-Type", "application/json")
                   .method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }

        HttpResponse<String> response;
        try {
            response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            trace(log, "network error: " + e.getMessage());
            throw new GeneratorException("Could not reach " + url, HttpStatus.BAD_GATEWAY, e);
        }
        trace(log, "-> HTTP " + response.statusCode());
        JsonNode parsed = parseQuietly(response.body());

        if (response.statusCode() / 100 != 2) {
            trace(log, "response: " + response.body());
            HttpStatus status = HttpStatus.resolve(response.statusCode());
            throw new GeneratorException(
                    "BMS HTTP " + response.statusCode() + ": " + response.body(),
                    status != null ? status : HttpStatus.BAD_GATEWAY, null);
        }
        if (parsed != null && parsed.path("success").isBoolean() && !parsed.path("success").asBoolean()) {
            trace(log, "response: " + response.body());
            throw new GeneratorException("BMS returned success=false: " + response.body());
        }
        return parsed;
    }

    private String toJson(Object payload) {
        try { return MAPPER.writeValueAsString(payload); }
        catch (Exception e) { throw new GeneratorException("Serialize lỗi", e); }
    }

    private JsonNode parseQuietly(String json) {
        if (json == null || json.isBlank()) return null;
        try { return MAPPER.readTree(json); }
        catch (Exception e) { return null; }
    }

    private GenerationResult.Builder result() { return GenerationResult.builder(type()); }

    private void trace(List<String> log, String line) {
        log.add("[" + LocalDateTime.now().format(TS) + "] " + line);
    }
}
