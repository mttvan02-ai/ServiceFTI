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
 * Runs the "Đánh giá" (TPP evaluation) step for an already-created Opportunity:
 * fetches the live per-opportunity criteria list, marks every criterion "Đạt"
 * (pass, code {@code 1}), and submits. Always required before an opportunity
 * can be approved (BR03) — see {@code memory/api-contracts.md} for the two
 * endpoints this chains together.
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
        return "GET criteria động theo opportunity rồi PUT evaluate — mặc định tất cả tiêu chí = Đạt.";
    }
    @Override public String signature() {
        return "GET {api.baseUrl}/evaluation-opportunities/criteria -> PUT .../evaluate";
    }

    @Override
    public List<ParamSpec> params() {
        return List.of(
                ParamSpec.password("bearerToken").label("Bearer token TPP (PUT evaluate)").required()
                        .hint("token của TPP — dùng cho bước PUT evaluate"),
                ParamSpec.password("criteriaToken").label("Bearer token Sale (GET criteria)")
                        .hint("token của Sale/tạo cơ hội — dùng cho GET criteria; để trống thì dùng chung bearerToken"),
                ParamSpec.text("baseUrl").label("API base URL")
                        .defaultValue(properties.getApi().getBaseUrl())
                        .hint("override only for a different environment"),
                ParamSpec.text("opportunityId").label("Mã cơ hội").required()
                        .placeholder("CH00085280"),
                ParamSpec.text("note").label("Ghi chú đánh giá")
                        .defaultValue("Đánh giá tự động qua tool")
        );
    }

    @Override
    public GenerationResult generate(Map<String, Object> p) {
        String bearerToken = Params.requireString(p, "bearerToken");
        // criteriaToken: sale token cho GET criteria (endpoint yêu cầu quyền khác TPP).
        // Nếu không truyền thì fallback về bearerToken (tương thích ngược).
        String criteriaToken = Params.optString(p, "criteriaToken", null);
        if (criteriaToken == null || criteriaToken.isBlank()) criteriaToken = bearerToken;
        String baseUrl = Params.optString(p, "baseUrl", properties.getApi().getBaseUrl()).replaceAll("/$", "");
        String opportunityId = Params.requireString(p, "opportunityId");
        String note = Params.optString(p, "note", "Đánh giá tự động qua tool");

        long t0 = System.currentTimeMillis();
        List<String> log = new ArrayList<>();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getApi().getTimeoutSeconds()))
                .build();

        // Diagnostic: GET opportunity để log tppCanApproval stored trong BMS
        try {
            JsonNode opp = send(client, "GET", baseUrl + "/opportunities/" + URLEncoder.encode(opportunityId, StandardCharsets.UTF_8), criteriaToken, null, log);
            JsonNode stored = opp.path("data").path("approval");
            trace(log, "BMS stored approval=" + stored.toString().substring(0, Math.min(200, stored.toString().length())));
        } catch (Exception ex) {
            trace(log, "diagnostic GET opp lỗi: " + ex.getMessage());
        }

        // Thử lần lượt nhiều variant — GET query, GET path, POST body, token fallback
        String encodedId = URLEncoder.encode(opportunityId, StandardCharsets.UTF_8);
        record CriteriaAttempt(String method, String url, String body, String token, String desc) {}
        // BMS frontend ALWAYS sends isFromConfig=true — missing this param → 403. Confirmed 2026-10-27.
        List<CriteriaAttempt> attempts = List.of(
            new CriteriaAttempt("GET",  baseUrl + "/evaluation-opportunities/criteria?opportunityId=" + encodedId + "&isFromConfig=true", null, criteriaToken, "GET+isFromConfig criteriaToken"),
            new CriteriaAttempt("GET",  baseUrl + "/evaluation-opportunities/criteria?opportunityId=" + encodedId + "&isFromConfig=true", null, bearerToken,   "GET+isFromConfig TPPtoken"),
            new CriteriaAttempt("GET",  baseUrl + "/evaluation-opportunities/criteria?opportunityId=" + encodedId, null, criteriaToken, "GET no-flag criteriaToken")
        );

        JsonNode criteriaResponse = null;
        for (CriteriaAttempt attempt : attempts) {
            trace(log, "[Step 1/2] " + attempt.desc() + " → " + attempt.method() + " " + attempt.url());
            try {
                JsonNode resp = send(client, attempt.method(), attempt.url(), attempt.token(), attempt.body(), log);
                JsonNode ev = resp.path("data").path("evaluations");
                if (ev.isArray() && !ev.isEmpty()) {
                    criteriaResponse = resp;
                    trace(log, "criteria OK via: " + attempt.desc());
                    break;
                }
                trace(log, "criteria rỗng, thử tiếp…");
            } catch (GeneratorException e) {
                String msg = e.getMessage() != null ? e.getMessage() : "";
                trace(log, "lỗi (" + msg.substring(0, Math.min(80, msg.length())) + ") — thử tiếp…");
            }
        }

        if (criteriaResponse == null) {
            // GET criteria thất bại với cả hai URL — skip evaluation, trả về warning.
            // Người dùng cần đánh giá thủ công trên BMS UI.
            trace(log, "⚠ Không lấy được criteria từ BMS — skip evaluation. Vào BMS UI đánh giá thủ công cho " + opportunityId);
            return result()
                    .put("opportunityId", opportunityId)
                    .put("skipped", true)
                    .put("reason", "GET /evaluation-opportunities/criteria trả 403 với mọi URL variant — đánh giá thủ công trên BMS UI")
                    .log(log)
                    .durationMs(System.currentTimeMillis() - t0)
                    .build();
        }

        JsonNode evaluations = criteriaResponse.path("data").path("evaluations");
        if (!evaluations.isArray() || evaluations.isEmpty()) {
            throw new GeneratorException(
                    "[Step 1/2 GET criteria] Criteria rỗng cho " + opportunityId + " — đã đánh giá rồi?");
        }

        List<Map<String, Object>> criteria = new ArrayList<>();
        for (JsonNode row : evaluations) {
            Map<String, Object> c = new LinkedHashMap<>();
            // The GET response's own `id` field is what the PUT expects back as `criteriaId` —
            // confirmed live 2026-08-25 (see memory/api-contracts.md). The GET's own
            // `criteriaId` field is always 0 and must NOT be used here.
            c.put("criteriaId", row.path("id").asLong());
            c.put("id", 0);
            c.put("criteria", row.path("criteria").asText(null));
            c.put("requirement", row.path("requirement").asText(null));
            c.put("description", row.path("description").asText(null));
            c.put("evaluation", PASS);
            c.put("note", null);
            criteria.add(c);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("opportunityId", opportunityId);
        payload.put("note", note);
        payload.put("criteria", criteria);

        String evaluateUrl = baseUrl + "/evaluation-opportunities/evaluate";
        trace(log, "[Step 2/2] PUT " + evaluateUrl + " (" + criteria.size() + " criteria, all Đạt)");
        JsonNode evaluateResponse;
        try {
            evaluateResponse = send(client, "PUT", evaluateUrl, bearerToken, toJson(payload), log);
        } catch (GeneratorException e) {
            throw new GeneratorException("[Step 2/2 PUT evaluate] " + e.getMessage(),
                    e.getStatus() != null ? e.getStatus() : HttpStatus.BAD_GATEWAY, e);
        }

        return result()
                .put("opportunityId", opportunityId)
                .put("criteriaCount", criteria.size())
                .put("responseBody", evaluateResponse)
                .log(log)
                .durationMs(System.currentTimeMillis() - t0)
                .build();
    }

    private JsonNode send(HttpClient client, String method, String url, String bearerToken, String body, List<String> log) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(properties.getApi().getTimeoutSeconds()))
                .header("Accept", "application/json, text/plain, */*")
                .header("Authorization", "Bearer " + bearerToken);
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
                    "BMS API rejected the request (HTTP " + response.statusCode() + "): " + response.body(),
                    status != null ? status : HttpStatus.BAD_GATEWAY, null);
        }
        if (parsed != null && parsed.path("success").isBoolean() && !parsed.path("success").asBoolean()) {
            trace(log, "response: " + response.body());
            throw new GeneratorException("BMS API returned success=false: " + response.body());
        }
        return parsed;
    }

    private String toJson(Object payload) {
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            throw new GeneratorException("Failed to serialize payload", e);
        }
    }

    private JsonNode parseQuietly(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private GenerationResult.Builder result() {
        return GenerationResult.builder(type());
    }

    private void trace(List<String> log, String line) {
        log.add("[" + LocalDateTime.now().format(TS) + "] " + line);
    }
}
