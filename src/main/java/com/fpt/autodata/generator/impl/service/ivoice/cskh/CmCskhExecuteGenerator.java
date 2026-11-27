package com.fpt.autodata.generator.impl.service.ivoice.cskh;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.autodata.generator.GenerationResult;
import com.fpt.autodata.generator.Generator;
import com.fpt.autodata.generator.GeneratorException;
import com.fpt.autodata.generator.ParamSpec;
import com.fpt.autodata.generator.Params;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CM thực hiện + ghi nhận kết quả cho Yêu cầu Hợp đồng CSKH — new-cm API.
 *
 * <p>Logic giống voice1900.cm-execute (new-cm chạy theo regCode, không phụ thuộc
 * loại dịch vụ). Base URL: {@code https://ftisapi-stag.fpt.net/api/cm}
 *
 * <p>Flow:
 * <ol>
 *   <li>GET /api/cm/registrations/detail?requestCode={regCode} → crTransactionId + transactionId</li>
 *   <li>POST /api/cm/registrations/create-contract {cmrTransactionId, noteOfApprover}</li>
 *   <li>GET /api/cm/registrations/check-registration?transactionId={id}&newStatus=4</li>
 *   <li>PUT /api/cm/registrations/update-status {status:4, cmrTransactionId, statusName:"Hoàn thành"}</li>
 * </ol>
 * Auth: Bearer token lấy qua POST /api/cm/auth/sso (CAS ticket, service=new-cm).</p>
 */
@Component
public class CmCskhExecuteGenerator implements Generator {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String NEW_CM_BASE = "https://ftisapi-stag.fpt.net/api/cm";
    private static final int STATUS_HOAN_THANH = 4;
    // 60s (không phải 20s như 1800/1900) — new-CM create-contract của CSKH quan sát
    // live 2026-11-26 mất >20s, gây curl exit=28 dù contract vẫn tạo được server-side.
    private static final int TIMEOUT_SECONDS = 60;

    @Override public String type()        { return "cskh.cm-execute"; }
    @Override public String displayName() { return "CM thực hiện YCHD CSKH — new-cm v3"; }
    @Override public String category()    { return "CSKH"; }
    @Override public String description() {
        return "Lookup CMR → CreateContract → UpdateStatus=4 trên new-cm API (/api/cm/registrations/*). " +
               "Cần cmToken từ POST /api/cm/auth/sso (CAS ticket, service=new-cm).";
    }
    @Override public String signature() {
        return "GET /api/cm/registrations/detail, POST /api/cm/registrations/create-contract, " +
               "PUT /api/cm/registrations/update-status";
    }

    @Override
    public List<ParamSpec> params() {
        return List.of(
                ParamSpec.password("cmToken").label("CM Bearer token (new-cm)").required()
                        .hint("lấy qua: POST /api/cm/auth/sso với CAS ticket, service=https://ftms-stag.fpt.net/new-cm"),
                ParamSpec.text("cmBaseUrl").label("New-CM API base URL").defaultValue(NEW_CM_BASE),
                ParamSpec.text("regCode").label("Mã đăng ký CM (regCode)").required()
                        .placeholder("CMR2614715")
                        .hint("lấy từ kết quả cskh.approve-registration → regCodes[0]"),
                ParamSpec.text("noteOfApprover").label("Ghi chú của CM")
                        .defaultValue("Tao HD tu dong qua tool")
        );
    }

    @Override
    public GenerationResult generate(Map<String, Object> p) {
        String cmToken        = Params.requireString(p, "cmToken");
        String baseUrl        = Params.optString(p, "cmBaseUrl", NEW_CM_BASE).replaceAll("/$", "");
        String regCode        = Params.requireString(p, "regCode");
        String noteOfApprover = Params.optString(p, "noteOfApprover", "Tao HD tu dong qua tool");

        long t0 = System.currentTimeMillis();
        List<String> log = new ArrayList<>();
        // Force HTTP/1.1 — same routing fix as voice1900.cm-execute
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .build();

        // Step 1: lookup registration detail → crTransactionId + transactionId
        String detailUrl = baseUrl + "/registrations/detail?requestCode=" + regCode;
        JsonNode reg = null;
        int MAX_DETAIL_RETRY = 6;
        for (int attempt = 1; attempt <= MAX_DETAIL_RETRY; attempt++) {
            if (attempt > 1) {
                trace(log, "new-CM chưa có " + regCode + ", chờ 5s thử lại (" + attempt + "/" + MAX_DETAIL_RETRY + ")…");
                try { Thread.sleep(5000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
            trace(log, "GET " + detailUrl + " (attempt " + attempt + ")");
            try {
                JsonNode detailResp = getJson(client, detailUrl, cmToken, log);
                reg = detailResp != null ? detailResp.path("data").path("registration") : null;
                if (reg != null && !reg.isMissingNode() && !reg.path("crTransactionId").asText("").isBlank()) break;
                reg = null;
            } catch (GeneratorException e) {
                String msg = e.getMessage() != null ? e.getMessage() : "";
                if (msg.contains("HTTP 404") || msg.contains("NotFound") || msg.contains("Không tìm thấy")) {
                    trace(log, "404/NotFound từ new-CM (attempt " + attempt + ") — thử lại…");
                    reg = null;
                } else {
                    throw e;
                }
            }
        }
        if (reg == null) {
            throw new GeneratorException("Không tìm thấy registration với regCode=" + regCode + " trong new-CM sau " + MAX_DETAIL_RETRY + " lần thử (mỗi lần cách 5s)");
        }

        int currentStatus      = reg.path("status").asInt(-1);
        String crTransactionId = reg.path("crTransactionId").asText(null);
        String transactionId   = reg.path("transactionId").asText(null);
        String contractNo      = reg.path("contractNo").asText(null);
        int isContractTemp     = reg.path("isContractTemp").asInt(0);
        String subContractNo   = reg.path("cmrSubContractNo").asText(null);
        boolean contractFinalized = isContractTemp == 2
                && subContractNo != null && !subContractNo.isBlank();

        if (crTransactionId == null || crTransactionId.isBlank()) {
            throw new GeneratorException("regCode=" + regCode + " chưa có crTransactionId — chưa qua bước BMS approve?");
        }
        trace(log, "regCode=" + regCode + " crTransactionId=" + crTransactionId
                + " status=" + currentStatus + " isContractTemp=" + isContractTemp + " subContractNo=" + subContractNo);

        if (currentStatus == STATUS_HOAN_THANH && contractFinalized) {
            trace(log, "status=4 + HĐ đã finalize (phụ lục=" + subContractNo + ") — bỏ qua, đã xong & đã đồng bộ BMS");
            return result()
                    .put("regCode", regCode)
                    .put("skipped", true)
                    .put("contractNo", contractNo)
                    .put("subContractNo", subContractNo)
                    .put("status", currentStatus)
                    .log(log)
                    .durationMs(System.currentTimeMillis() - t0)
                    .build();
        }

        // ── DEM flow thật (memory newcm-dem-bms-sync): checkRegistration(newStatus:4)
        // → create-contract → update-status. create-contract là bước SINH PHỤ LỤC
        // (cmrSubContractNo) + đẩy "DEM phê duyệt" về BMS (cmApprovalStatus 2→1).
        // ⚠️ User directive 2026-11-26: CHỈ update-status=4 khi create-contract ĐÃ
        // finalize (sinh được cmrSubContractNo). Nếu KHÔNG → tuyệt đối không
        // update-status, throw ngay, để record KHÔNG bị khóa ở trạng thái lệch
        // "status=4 nhưng chưa sync DEM" (còn duyệt DEM tay trên new-CM được).
        if (!contractFinalized) {
            // Step A: check-registration(newStatus:4) — precondition của DEM flow
            if (transactionId != null && !transactionId.isBlank()) {
                String checkUrl = baseUrl + "/registrations/check-registration?transactionId=" + transactionId + "&newStatus=" + STATUS_HOAN_THANH;
                trace(log, "GET " + checkUrl);
                try {
                    JsonNode checkResp = getJson(client, checkUrl, cmToken, log);
                    if (checkResp != null && checkResp.path("data").path("isError").asBoolean(false)) {
                        trace(log, "check-registration cảnh báo: " + checkResp.path("data").path("message").asText());
                    }
                } catch (GeneratorException e) {
                    trace(log, "check-registration lỗi (bỏ qua): " + shortMsg(e));
                }
            }

            // Step B: create-contract (sinh phụ lục + đồng bộ DEM về BMS)
            Map<String, Object> createBody = new LinkedHashMap<>();
            createBody.put("cmrTransactionId", crTransactionId);
            createBody.put("noteOfApprover", noteOfApprover);
            String createUrl = baseUrl + "/registrations/create-contract";
            trace(log, "POST " + createUrl + " cmrTransactionId=" + crTransactionId);
            GeneratorException createErr = null;
            try {
                postJson(client, createUrl, cmToken, toJson(createBody), log);
            } catch (GeneratorException e) {
                createErr = e;
                trace(log, "create-contract trả lỗi: " + shortMsg(e) + " — verify đã finalize chưa…");
            }

            // Verify: create-contract chỉ THÀNH CÔNG khi để lại dấu finalize trên
            // new-CM — sinh phụ lục (cmrSubContractNo) HOẶC ghi được noteOfApprover
            // (cả 2 đều là hệ quả của create-contract theo memory newcm-dem-bms-sync;
            // dùng noteOfApprover để không báo nhầm với voice không có phụ lục).
            try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            JsonNode recheck = getJson(client, detailUrl, cmToken, log);
            JsonNode reg2 = recheck != null ? recheck.path("data").path("registration") : null;
            String sub2  = reg2 != null ? reg2.path("cmrSubContractNo").asText(null) : null;
            String note2 = reg2 != null ? reg2.path("noteOfApprover").asText(null) : null;
            boolean finalized = (sub2 != null && !sub2.isBlank()) || (note2 != null && !note2.isBlank());
            if (!finalized) {
                // CHƯA finalize (chưa sinh phụ lục + chưa ghi note → chưa sync DEM về
                // BMS) → KHÔNG update-status (tránh khóa record status=4 lệch).
                String base = createErr != null ? shortMsg(createErr) : "new-CM không sinh phụ lục / không ghi note / không đồng bộ DEM về BMS";
                throw new GeneratorException("create-contract CHƯA finalize cho " + regCode
                        + " (" + base + "). ĐÃ DỪNG — KHÔNG đánh dấu status=4 để record không bị khóa. "
                        + "→ Vào new-CM duyệt DEM tay cho phiếu này, hoặc thử lại.");
            }
            subContractNo = sub2;
            contractNo = reg2.path("contractNo").asText(contractNo);
            isContractTemp = reg2.path("isContractTemp").asInt(isContractTemp);
            trace(log, "✓ create-contract finalize — phụ lục=" + sub2 + ", note=" + note2 + ", contractNo=" + contractNo);
        } else {
            trace(log, "HĐ đã finalize (subContractNo=" + subContractNo + ") — bỏ qua create-contract");
        }

        // Step C: update status = 4 (Hoàn thành) — chỉ tới đây khi ĐÃ finalize (có phụ lục).
        Map<String, Object> updateBody = new LinkedHashMap<>();
        updateBody.put("status", STATUS_HOAN_THANH);
        updateBody.put("cmrTransactionId", crTransactionId);
        updateBody.put("statusName", "Hoàn thành");
        String updateUrl = baseUrl + "/registrations/update-status";
        trace(log, "PUT " + updateUrl + " status=" + STATUS_HOAN_THANH);
        try {
            putJson(client, updateUrl, cmToken, toJson(updateBody), log);
        } catch (GeneratorException e) {
            // Đã finalize (có phụ lục) rồi — update-status "đã hoàn thành" là OK.
            trace(log, "update-status báo lỗi nhưng HĐ đã finalize — bỏ qua: " + shortMsg(e));
        }

        // Final: reload to get contract numbers
        JsonNode finalResp = getJson(client, detailUrl, cmToken, log);
        JsonNode finalReg  = finalResp != null ? finalResp.path("data").path("registration") : null;

        return result()
                .put("regCode", regCode)
                .put("skipped", false)
                .put("contractNo", finalReg != null ? finalReg.path("contractNo").asText(null) : contractNo)
                .put("subContractNo", finalReg != null ? finalReg.path("cmrSubContractNo").asText(null) : subContractNo)
                .put("isContractTemp", finalReg != null ? finalReg.path("isContractTemp").asInt(isContractTemp) : isContractTemp)
                .put("status", STATUS_HOAN_THANH)
                .put("statusName", "Hoàn thành")
                .log(log)
                .durationMs(System.currentTimeMillis() - t0)
                .build();
    }

    /** curl workaround — Java HttpClient bị route sang API CM V1 (404). Xem Cm1900ExecuteGenerator. */
    private JsonNode getJson(HttpClient client, String url, String token, List<String> log) {
        try {
            trace(log, "curl token prefix=" + (token != null && token.length() > 30 ? token.substring(0, 30) : token)
                + " len=" + (token != null ? token.length() : 0));
            ProcessBuilder pb = new ProcessBuilder(
                "curl", "-s", "-m", String.valueOf(TIMEOUT_SECONDS),
                "--noproxy", "*",
                "-H", "Accept: application/json, text/plain, */*",
                "-H", "Authorization: Bearer " + token,
                url
            );
            pb.redirectErrorStream(false);
            Process proc = pb.start();
            String body = new String(proc.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            int exitCode = proc.waitFor();
            trace(log, "-> curl exit=" + exitCode + " body_start=" + body.substring(0, Math.min(100, body.length())));
            JsonNode parsed = parseQuietly(body);
            if (parsed == null) {
                throw new GeneratorException("new-cm API: curl trả response không parse được (exit=" + exitCode + "): " + body.substring(0, Math.min(200, body.length())));
            }
            String status = parsed.path("status").asText("NOTOK");
            if (!"OK".equalsIgnoreCase(status)) {
                String errMsg = parsed.path("error").path("message").asText(parsed.toString());
                trace(log, "response: " + body);
                throw new GeneratorException("new-cm API HTTP error (curl): " + errMsg,
                    HttpStatus.NOT_FOUND, null);
            }
            return parsed;
        } catch (GeneratorException e) {
            throw e;
        } catch (Exception e) {
            throw new GeneratorException("Không gọi được new-CM: " + e.getMessage(), HttpStatus.BAD_GATEWAY, e);
        }
    }

    private JsonNode postJson(HttpClient client, String url, String token, String body, List<String> log) {
        return curlJson("POST", url, token, body, log);
    }

    private JsonNode putJson(HttpClient client, String url, String token, String body, List<String> log) {
        return curlJson("PUT", url, token, body, log);
    }

    private JsonNode curlJson(String method, String url, String token, String body, List<String> log) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "curl", "-s", "-m", String.valueOf(TIMEOUT_SECONDS),
                "--noproxy", "*",
                "-X", method,
                "-H", "Accept: application/json, text/plain, */*",
                "-H", "Authorization: Bearer " + token,
                "-H", "Content-Type: application/json",
                "-d", body,
                url
            );
            pb.redirectErrorStream(false);
            Process proc = pb.start();
            String respBody = new String(proc.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            int exitCode = proc.waitFor();
            trace(log, "-> curl " + method + " exit=" + exitCode + " body_start=" + respBody.substring(0, Math.min(100, respBody.length())));
            JsonNode parsed = parseQuietly(respBody);
            if (parsed == null) {
                throw new GeneratorException("new-cm API: curl " + method + " response không parse được (exit=" + exitCode + "): " + respBody.substring(0, Math.min(200, respBody.length())));
            }
            String status = parsed.path("status").asText("NOTOK");
            if (!"OK".equalsIgnoreCase(status)) {
                String errMsg = parsed.path("error").path("message").asText(parsed.toString());
                trace(log, "response: " + respBody);
                throw new GeneratorException("new-cm API " + method + " error: " + errMsg, HttpStatus.BAD_GATEWAY, null);
            }
            return parsed;
        } catch (GeneratorException e) {
            throw e;
        } catch (Exception e) {
            throw new GeneratorException("Không gọi được new-CM (" + method + "): " + e.getMessage(), HttpStatus.BAD_GATEWAY, e);
        }
    }

    private String toJson(Object o) {
        try { return MAPPER.writeValueAsString(o); }
        catch (Exception e) { throw new GeneratorException("Serialize JSON lỗi", e); }
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

    private String shortMsg(Exception e) {
        String m = e.getMessage() != null ? e.getMessage() : "";
        return m.substring(0, Math.min(140, m.length()));
    }
}
