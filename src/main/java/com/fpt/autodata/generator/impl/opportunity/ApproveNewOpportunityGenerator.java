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
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BGĐ duyệt tạo cơ hội mới — bước bắt buộc trước khi TPP có thể đánh giá.
 *
 * BMS workflow (xác nhận từ ApproveNewOppModal.js 2026-10-27):
 *   POST /api/approval-opportunities/approve-new-opportunity
 *   body: { opportunityId, approvalStatus: 1, reason, isActiveOpp: true }
 *
 * approvalStatus: 1 = Phê duyệt (nt=1 trong JS bundle).
 * isActiveOpp: true = Cơ hội "Hoạt động" sau khi duyệt.
 *
 * Phải chạy trước evaluation.evaluate — thiếu bước này → criteria 403.
 */
@Component
public class ApproveNewOpportunityGenerator implements Generator {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int APPROVAL_STATUS_APPROVE = 1;

    private final AutodataProperties properties;

    public ApproveNewOpportunityGenerator(AutodataProperties properties) {
        this.properties = properties;
    }

    @Override public String type()        { return "opportunity.approve-new"; }
    @Override public String displayName() { return "BGĐ duyệt tạo cơ hội mới"; }
    @Override public String category()    { return "Opportunity"; }
    @Override public String description() {
        return "POST /approval-opportunities/approve-new-opportunity — bước BGĐ duyệt trước evaluation. Thiếu bước này → criteria 403.";
    }
    @Override public String signature() {
        return "POST {api.baseUrl}/approval-opportunities/approve-new-opportunity";
    }

    @Override
    public List<ParamSpec> params() {
        return List.of(
                ParamSpec.password("bearerToken").label("Bearer token BGĐ").required()
                        .hint("token của BGĐ (usersCanApproval) — người duyệt tạo cơ hội"),
                ParamSpec.text("baseUrl").label("API base URL")
                        .defaultValue(properties.getApi().getBaseUrl()),
                ParamSpec.text("opportunityId").label("Mã cơ hội").required()
                        .placeholder("CH00086488"),
                ParamSpec.text("reason").label("Ý kiến phê duyệt")
                        .defaultValue("Phe duyet tao co hoi tu dong qua tool")
        );
    }

    @Override
    public GenerationResult generate(Map<String, Object> p) {
        String bearerToken   = Params.requireString(p, "bearerToken");
        String baseUrl       = Params.optString(p, "baseUrl", properties.getApi().getBaseUrl()).replaceAll("/$", "");
        String opportunityId = Params.requireString(p, "opportunityId");
        String reason        = Params.optString(p, "reason", "Phe duyet tao co hoi tu dong qua tool");

        long t0 = System.currentTimeMillis();
        List<String> log = new ArrayList<>();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getApi().getTimeoutSeconds()))
                .build();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("opportunityId", opportunityId);
        payload.put("approvalStatus", APPROVAL_STATUS_APPROVE);
        payload.put("reason", reason);
        payload.put("isActiveOpp", true);

        String url = baseUrl + "/approval-opportunities/approve-new-opportunity";
        trace(log, "POST " + url + " opportunityId=" + opportunityId + " approvalStatus=1 isActiveOpp=true");

        String body;
        try { body = MAPPER.writeValueAsString(payload); }
        catch (Exception e) { throw new GeneratorException("Serialize lỗi", e); }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(properties.getApi().getTimeoutSeconds()))
                .header("Accept", "application/json, text/plain, */*")
                .header("Authorization", "Bearer " + bearerToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            trace(log, "network error: " + e.getMessage());
            throw new GeneratorException("Không kết nối được " + url, HttpStatus.BAD_GATEWAY, e);
        }
        trace(log, "-> HTTP " + response.statusCode());

        if (response.statusCode() / 100 != 2) {
            trace(log, "response: " + response.body());
            HttpStatus st = HttpStatus.resolve(response.statusCode());
            throw new GeneratorException(
                    "BMS HTTP " + response.statusCode() + ": " + response.body(),
                    st != null ? st : HttpStatus.BAD_GATEWAY, null);
        }

        JsonNode parsed = parseQuietly(response.body());
        if (parsed != null && parsed.path("success").isBoolean() && !parsed.path("success").asBoolean()) {
            // Already approved → không phải lỗi, bỏ qua
            String msg = parsed.path("errors").toString();
            if (msg.contains("đã được phê duyệt") || msg.contains("already")) {
                trace(log, "Cơ hội đã được phê duyệt trước đó — bỏ qua");
            } else {
                throw new GeneratorException("BMS returned success=false: " + response.body());
            }
        }

        return GenerationResult.builder(type())
                .put("opportunityId", opportunityId)
                .put("approvalStatus", APPROVAL_STATUS_APPROVE)
                .put("isActiveOpp", true)
                .log(log)
                .durationMs(System.currentTimeMillis() - t0)
                .build();
    }

    private JsonNode parseQuietly(String json) {
        if (json == null || json.isBlank()) return null;
        try { return MAPPER.readTree(json); }
        catch (Exception e) { return null; }
    }

    private void trace(List<String> log, String line) {
        log.add("[" + LocalDateTime.now().format(TS) + "] " + line);
    }
}
