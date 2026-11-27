package com.fpt.autodata.generator.impl.service.ivoice.cskh;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.List;
import java.util.Map;

/**
 * Lookup danh sách số CSKH available (SM v2) để UI chọn trước khi tạo YCHD.
 *
 * <p>Khác 1800/1900: CSKH lookup theo <b>Nền tảng (platform) + Đối tác (partnerId)</b>,
 * KHÔNG dùng locationId. {@code serviceType} ở đây là mã platform composite
 * (81 = VOIP, 82 = Brandname), không phải 8. Số chỉ hiện khi đã book sẵn ở màn đầu số
 * cho đúng cặp platform + partner + MST.</p>
 */
@Component
public class GetCskhPhoneBookedGenerator implements Generator {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SM_BASE = "https://ftisapi-stag.fpt.net/sm/v2";
    private static final int TIMEOUT_SECONDS = 20;
    private static final int PLATFORM_VOIP = 81;

    @Override public String type()        { return "cskh.get-phone-booked"; }
    @Override public String displayName() { return "Tải số CSKH available (SM v2)"; }
    @Override public String category()    { return "CSKH"; }
    @Override public String description() {
        return "POST /sm/v2/ivoice/api/registration/get-phone-booked-number (serviceType=platform 81/82 + partnerId) — lấy danh sách số CSKH đã book để chọn trước khi tạo YCHD.";
    }
    @Override public String signature() {
        return "POST /sm/v2/ivoice/api/registration/get-phone-booked-number";
    }

    @Override
    public List<ParamSpec> params() {
        return List.of(
                ParamSpec.password("smToken").label("SM Bearer token").required()
                        .hint("lấy qua auth.get-sm-token-api"),
                ParamSpec.text("taxId").label("Mã số thuế KH").required()
                        .hint("tự động lấy từ Mã cơ hội"),
                ParamSpec.enumOf("platform", List.of("81", "82"))
                        .label("Nền tảng (platform)").defaultValue("81")
                        .hint("81 = VOIP, 82 = Brandname"),
                ParamSpec.number("partnerId").label("Đối tác triển khai (partnerId)").required()
                        .defaultValue(2)
                        .hint("Telesales=1, ViHAT=2, ITS=3...")
        );
    }

    @Override
    public GenerationResult generate(Map<String, Object> p) {
        String smToken    = Params.requireString(p, "smToken");
        String taxId      = Params.requireString(p, "taxId");
        int    platform   = (int) Params.optInt(p, "platform", PLATFORM_VOIP);
        int    partnerId  = (int) Params.optInt(p, "partnerId", 2);

        long t0 = System.currentTimeMillis();
        List<String> log = new ArrayList<>();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS)).build();

        ObjectNode body = MAPPER.createObjectNode();
        body.put("serviceType", platform);
        body.put("taxID", taxId);
        body.put("regDetailId", -1);
        body.put("partnerId", partnerId);

        String url = SM_BASE + "/ivoice/api/registration/get-phone-booked-number";
        trace(log, "POST " + url + " platform=" + platform + " partnerId=" + partnerId + " taxId=" + taxId);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header("Accept", "application/json, text/plain, */*")
                .header("Authorization", "Bearer " + smToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(toJson(body)))
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
        JsonNode phones = root != null ? root.path("data") : null;
        int count = (phones != null && phones.isArray()) ? phones.size() : 0;
        trace(log, "Tìm thấy " + count + " số CSKH available");

        return GenerationResult.builder(type())
                .put("phones", phones)
                .put("count", count)
                .log(log)
                .durationMs(System.currentTimeMillis() - t0)
                .build();
    }

    private String toJson(Object o) {
        try { return MAPPER.writeValueAsString(o); }
        catch (Exception e) { throw new GeneratorException("Serialize lỗi", e); }
    }

    private JsonNode parseQuietly(String json) {
        if (json == null || json.isBlank()) return null;
        try { return MAPPER.readTree(json); }
        catch (Exception ignored) { return null; }
    }

    private void trace(List<String> log, String line) {
        log.add("[" + LocalDateTime.now().format(TS) + "] " + line);
    }
}
