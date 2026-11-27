package com.fpt.autodata.generator.impl.service.sm.oncallcx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.autodata.generator.GenerationResult;
import com.fpt.autodata.generator.Generator;
import com.fpt.autodata.generator.GeneratorException;
import com.fpt.autodata.generator.ParamSpec;
import com.fpt.autodata.generator.Params;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Validate Email user CRM/CX cho OnCallCX trước khi tạo YCHD.
 *
 * <p>Capture live 2026-11-27: form OnCallCX require Email user CRM/CX; gọi endpoint
 * này trả {@code {isValid, message}}. Dùng SM token (auth.get-sm-token-api).</p>
 */
@Component
public class ValidateSmOncallEmailGenerator implements Generator {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SM_SD_BASE = "https://ftisapi-stag.fpt.net/sm/v2/sd/api";
    private static final int TIMEOUT_SECONDS = 20;

    @Override public String type()        { return "sm.oncall-validate-email"; }
    @Override public String displayName() { return "Validate Email user CRM/CX (OnCallCX)"; }
    @Override public String category()    { return "SM"; }
    @Override public String description() {
        return "GET /sm/v2/sd/api/registrations/oncall-cx-service-infor?refCode=&email= — kiểm tra Email user CRM/CX hợp lệ cho OnCallCX.";
    }
    @Override public String signature() {
        return "GET /sm/v2/sd/api/registrations/oncall-cx-service-infor";
    }

    @Override
    public List<ParamSpec> params() {
        return List.of(
                ParamSpec.password("smToken").label("SM Bearer token").required()
                        .hint("lấy qua auth.get-sm-token-api"),
                ParamSpec.text("refCode").label("RefCode").required()
                        .hint("vd CH00086717_100847 — từ ivoice.lookup-opportunity"),
                ParamSpec.text("email").label("Email user CRM/CX").required()
                        .hint("email user CRM/CX của KH")
        );
    }

    @Override
    public GenerationResult generate(Map<String, Object> p) {
        String smToken = Params.requireString(p, "smToken");
        String refCode = Params.requireString(p, "refCode");
        String email   = Params.requireString(p, "email");

        long t0 = System.currentTimeMillis();
        List<String> log = new ArrayList<>();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS)).build();

        String url = SM_SD_BASE + "/registrations/oncall-cx-service-infor?refCode=" + enc(refCode)
                + "&email=" + enc(email);
        trace(log, "GET " + url);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header("Accept", "application/json, text/plain, */*")
                .header("Authorization", "Bearer " + smToken)
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new GeneratorException("Không kết nối được SM API: " + e.getMessage(), HttpStatus.BAD_GATEWAY, e);
        }
        trace(log, "-> HTTP " + response.statusCode());
        if (response.statusCode() / 100 != 2) {
            HttpStatus st = HttpStatus.resolve(response.statusCode());
            throw new GeneratorException("SM API HTTP " + response.statusCode() + ": " + response.body(),
                    st != null ? st : HttpStatus.BAD_GATEWAY, null);
        }

        JsonNode root = parseQuietly(response.body());
        JsonNode data = root != null ? root.path("data") : null;
        boolean isValid = data != null && data.path("isValid").asBoolean(false);
        String  message = data != null ? data.path("message").asText(null) : null;
        trace(log, "isValid=" + isValid + (message != null ? " message=" + message : ""));

        return GenerationResult.builder(type())
                .put("isValid", isValid)
                .put("message", message)
                .put("data", data)
                .log(log)
                .durationMs(System.currentTimeMillis() - t0)
                .build();
    }

    private String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private JsonNode parseQuietly(String json) {
        if (json == null || json.isBlank()) return null;
        try { return MAPPER.readTree(json); }
        catch (Exception ignored) { return null; }
    }

    private void trace(List<String> log, String line) {
        log.add("[" + LocalDateTime.now().format(TS) + "] " + line);
    }
}
