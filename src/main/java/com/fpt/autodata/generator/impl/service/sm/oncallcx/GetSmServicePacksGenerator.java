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
 * Lấy danh sách gói dịch vụ SM (Tổng đài) theo serviceType + serviceGroup — để UI
 * render bảng "Gói dịch vụ" trong form tạo YCHD OnCallCX.
 *
 * <p>Capture live 2026-11-27 (xem memory/api-contracts.md §SM): OnCallCX =
 * serviceGroup 13000 (Tổng đài) / serviceType 13007, đi cùng luồng bms-service như
 * CSKH. Endpoint này thuộc SM API (sm/v2/sd), cần SM token (auth.get-sm-token-api).</p>
 */
@Component
public class GetSmServicePacksGenerator implements Generator {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SM_SD_BASE = "https://ftisapi-stag.fpt.net/sm/v2/sd/api";
    private static final int TIMEOUT_SECONDS = 20;

    @Override public String type()        { return "sm.service-packs"; }
    @Override public String displayName() { return "Tải gói dịch vụ SM (Tổng đài)"; }
    @Override public String category()    { return "SM"; }
    @Override public String description() {
        return "GET /sm/v2/sd/api/master-datas/service-packs?serviceType=&serviceGroup= — danh sách gói dịch vụ SM (OnCallCX 13007 / Tổng đài 13000).";
    }
    @Override public String signature() {
        return "GET /sm/v2/sd/api/master-datas/service-packs";
    }

    @Override
    public List<ParamSpec> params() {
        return List.of(
                ParamSpec.password("smToken").label("SM Bearer token").required()
                        .hint("lấy qua auth.get-sm-token-api"),
                ParamSpec.number("serviceType").label("Loại dịch vụ (serviceType)").required()
                        .defaultValue(13007)
                        .hint("OnCallCX=13007, Tổng đài oncall=13002"),
                ParamSpec.number("serviceGroup").label("Nhóm dịch vụ (serviceGroup)").required()
                        .defaultValue(13000)
                        .hint("Tổng đài=13000")
        );
    }

    @Override
    public GenerationResult generate(Map<String, Object> p) {
        String smToken      = Params.requireString(p, "smToken");
        int    serviceType  = (int) Params.optInt(p, "serviceType", 13007);
        int    serviceGroup = (int) Params.optInt(p, "serviceGroup", 13000);

        long t0 = System.currentTimeMillis();
        List<String> log = new ArrayList<>();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS)).build();

        String url = SM_SD_BASE + "/master-datas/service-packs?serviceType="
                + enc(String.valueOf(serviceType)) + "&serviceGroup=" + enc(String.valueOf(serviceGroup));
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

        JsonNode root  = parseQuietly(response.body());
        JsonNode packs = root != null ? root.path("data") : null;
        int count = (packs != null && packs.isArray()) ? packs.size() : 0;
        trace(log, "Tìm thấy " + count + " gói dịch vụ");

        return GenerationResult.builder(type())
                .put("packs", packs)
                .put("count", count)
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
