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
 * Lookup đầu số iVoice khả dụng theo Số HĐ Voice — để gắn vào tab "Đầu số đính kèm"
 * khi tạo YCHD OnCallCX.
 *
 * <p>Capture live 2026-11-27: OnCallCX bắt buộc ≥1 đầu số, lấy từ 1 HĐ Voice (iVoice)
 * có sẵn còn đầu số thuộc đúng KH. Trả {@code data:[]} nếu HĐ không có đầu số khả dụng.
 * Dùng SM token (auth.get-sm-token-api).</p>
 */
@Component
public class GetSmOncallPhoneByContractGenerator implements Generator {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SM_SD_BASE = "https://ftisapi-stag.fpt.net/sm/v2/sd/api";
    private static final int TIMEOUT_SECONDS = 20;

    @Override public String type()        { return "sm.oncall-phone-by-contract"; }
    @Override public String displayName() { return "Tải đầu số theo HĐ Voice (OnCallCX)"; }
    @Override public String category()    { return "SM"; }
    @Override public String description() {
        return "GET /sm/v2/sd/api/master-datas/ivoice-phone-number-contract?contract={soHDVoice} — đầu số iVoice khả dụng để đính kèm OnCallCX (data:[] nếu HĐ không còn đầu số).";
    }
    @Override public String signature() {
        return "GET /sm/v2/sd/api/master-datas/ivoice-phone-number-contract";
    }

    @Override
    public List<ParamSpec> params() {
        return List.of(
                ParamSpec.password("smToken").label("SM Bearer token").required()
                        .hint("lấy qua auth.get-sm-token-api"),
                ParamSpec.text("contract").label("Số HĐ Voice (iVoice)").required()
                        .hint("vd SGVO18050 — HĐ iVoice còn đầu số của KH")
        );
    }

    @Override
    public GenerationResult generate(Map<String, Object> p) {
        String smToken  = Params.requireString(p, "smToken");
        String contract = Params.requireString(p, "contract");

        long t0 = System.currentTimeMillis();
        List<String> log = new ArrayList<>();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS)).build();

        String url = SM_SD_BASE + "/master-datas/ivoice-phone-number-contract?contract=" + enc(contract);
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

        JsonNode root   = parseQuietly(response.body());
        JsonNode phones = root != null ? root.path("data") : null;
        int count = (phones != null && phones.isArray()) ? phones.size() : 0;
        trace(log, "Tìm thấy " + count + " đầu số khả dụng cho HĐ " + contract
                + (count == 0 ? " (HĐ không còn đầu số / sai KH)" : ""));

        return GenerationResult.builder(type())
                .put("phones", phones)
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
