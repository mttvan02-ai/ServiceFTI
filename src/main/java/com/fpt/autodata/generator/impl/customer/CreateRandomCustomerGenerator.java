package com.fpt.autodata.generator.impl.customer;

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
import java.util.concurrent.ThreadLocalRandom;

/**
 * Creates a throwaway customer on the CM system — used as a fallback so
 * {@code opportunity.api} can be run without hand-picking a real
 * {@code customerId} first.
 *
 * <p>Supports 4 customer kinds:</p>
 * <ul>
 *   <li><b>to-chuc-nuoc-ngoai</b> — customerType:2 + profileType:4 (Tổ chức nước ngoài).
 *       Simplest variant — no province/ward required. Uses {@code taxNo} field (10 digits)
 *       to override the auto-generated {@code FTINN*} code. Confirmed: {@code identifyId}
 *       is NOT the MST field — it is the CCCD/passport field (stored separately). The
 *       {@code taxNo} field in CreateCustomerInputDto directly overrides the auto-gen.</li>
 *   <li><b>ca-nhan-nuoc-ngoai</b> — customerType:1 + profileType:2 (Cá nhân nước ngoài).
 *       Uses {@code identifyId} (CCCD/passport number, 10–13 digits) + {@code passportDate},
 *       {@code issuedBy}, {@code countryCode}. No province/ward required.</li>
 *   <li><b>to-chuc-trong-nuoc</b> — customerType:2 + profileType:3 (Doanh nghiệp trong nước).
 *       Requires {@code cityId} (Hà Nội = 1), {@code wardId}, {@code streetName}, and a
 *       {@code contacts} entry with {@code contactType:"2"} (Đại diện pháp luật) + position.</li>
 *   <li><b>ca-nhan-trong-nuoc</b> — customerType:1 + profileType:1 (Cá nhân trong nước).
 *       Requires {@code identifyId} (12-digit CCCD), {@code passportDate} (issued date),
 *       {@code issuedBy}, {@code cityId}, {@code wardId}, {@code streetName}.</li>
 * </ul>
 *
 * <p>Field discovery method (2026-11-28):
 * <ol>
 *   <li>Fetched {@code GET /api/cm/swagger/v1/swagger.json} → found
 *       {@code CreateCustomerInputDto} with field {@code taxNo} (string, nullable).</li>
 *   <li>Probed {@code POST /api/cm/customer/create} with {@code taxNo:"9876543210"} →
 *       response returned same value, confirming override works.</li>
 *   <li>Fetched {@code GET /api/cm/master-data/profile-types-and-customer-classifications/tree-select}
 *       → enumerated all profileType codes (1=trong nước cá nhân, 2=nước ngoài cá nhân,
 *       3=DN trong nước, 4=tổ chức nước ngoài, 5=cơ quan nhà nước).</li>
 *   <li>Fetched {@code GET /api/cm/contact/get-by-cuscode?customerCode=FTI260899} (existing
 *       domestic org) → contactType:"2" = "Đại diện pháp luật", contactType:"1" = người liên hệ.</li>
 * </ol>
 * </p>
 *
 * <p>Confirmed live: FTI260914 (to-chuc-nuoc-ngoai with taxNo), FTI260916 (ca-nhan-trong-nuoc),
 * FTI260917 (to-chuc-trong-nuoc) created this way and usable as {@code customerId}.</p>
 */
@Component
public class CreateRandomCustomerGenerator implements Generator {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CREATE_URL = "https://ftisapi-stag.fpt.net/api/cm/customer/create";
    private static final int TIMEOUT_SECONDS = 20;

    // profileType + customerType combos confirmed live 2026-11-28
    private static final String KIND_ORG_FOREIGN    = "to-chuc-nuoc-ngoai";   // ct=2, pt=4
    private static final String KIND_IND_FOREIGN    = "ca-nhan-nuoc-ngoai";   // ct=1, pt=2
    private static final String KIND_ORG_DOMESTIC   = "to-chuc-trong-nuoc";   // ct=2, pt=3
    private static final String KIND_IND_DOMESTIC   = "ca-nhan-trong-nuoc";   // ct=1, pt=1

    // Domestic address defaults (Hà Nội, Xã Vĩnh Thanh, wardId=508 confirmed from master data)
    private static final int DOMESTIC_CITY_ID = 1;    // Hà Nội
    private static final int DOMESTIC_WARD_ID = 508;  // Xã Vĩnh Thanh, Hà Nội

    @Override public String type()        { return "customer.create-random"; }
    @Override public String displayName() { return "Tạo khách hàng ngẫu nhiên (CM)"; }
    @Override public String category()    { return "Opportunity"; }
    @Override public String description() {
        return "POST api/cm/customer/create — tạo nhanh 1 khách hàng test để lấy customerId. " +
               "Hỗ trợ 4 loại: to-chuc-nuoc-ngoai (mặc định), ca-nhan-nuoc-ngoai, " +
               "to-chuc-trong-nuoc, ca-nhan-trong-nuoc. " +
               "taxNo/CCCD đều là thuần số 10–12 chữ số.";
    }
    @Override public String signature() { return "POST " + CREATE_URL; }

    @Override
    public List<ParamSpec> params() {
        return List.of(
                ParamSpec.password("cmToken").label("CM bearer token").required()
                        .hint("lấy qua generator \"auth.get-cm-token\""),
                ParamSpec.enumOf("customerKind",
                        List.of(KIND_ORG_FOREIGN, KIND_IND_FOREIGN,
                                KIND_ORG_DOMESTIC, KIND_IND_DOMESTIC))
                        .label("Loại khách hàng").defaultValue(KIND_ORG_FOREIGN)
                        .hint("to-chuc-nuoc-ngoai (mặc định, không cần Tỉnh/Phường) | " +
                              "ca-nhan-nuoc-ngoai | to-chuc-trong-nuoc (cần Tỉnh+Phường+ĐDPL) | " +
                              "ca-nhan-trong-nuoc (cần Tỉnh+Phường+CCCD 12 số)"),
                ParamSpec.text("namePrefix").label("Tiền tố tên KH").defaultValue("QA Auto Test")
        );
    }

    // Chỉ letters — customerType:1 (cá nhân) áp pattern tên người thật; số trong tên bị 400
    // "Tên khách hàng không hợp lệ" (xác nhận sống 2026-08-27). Tổ chức không kiểm tra chặt.
    private static final String[] SURNAMES    = {"Nguyen", "Tran", "Le", "Pham", "Hoang",
                                                  "Vu", "Dang", "Bui", "Do", "Ngo", "Duong", "Ly"};
    private static final String[] GIVEN_NAMES = {"An", "Binh", "Chi", "Dung", "Giang",
                                                  "Hoa", "Khang", "Linh", "Minh", "Nam",
                                                  "Phuc", "Quyen", "Thao", "Tuan", "Yen"};

    @Override
    public GenerationResult generate(Map<String, Object> p) {
        String cmToken   = Params.requireString(p, "cmToken");
        String namePrefix = Params.optString(p, "namePrefix", "QA Auto Test");
        String kind      = Params.optString(p, "customerKind", KIND_ORG_FOREIGN);

        int suffix = ThreadLocalRandom.current().nextInt(100000, 999999);
        String phone = "09" + ThreadLocalRandom.current().nextInt(10000000, 99999999);

        Map<String, Object> payload = new LinkedHashMap<>();
        String customerName;

        switch (kind) {
            case KIND_IND_FOREIGN -> {
                // ── Cá nhân nước ngoài (customerType=1, profileType=2) ────────────────
                // identifyId = CCCD/passport number (10–13 digits).
                // passportDate + issuedBy required. No province/ward needed.
                customerName = randomPersonName();
                String slug  = (customerName + suffix).toLowerCase().replaceAll("[^a-z0-9]+", "");
                payload.put("customerName", customerName);
                payload.put("email",        slug + "@example-test.vn");
                payload.put("phone",        phone);
                payload.put("groupCode",    "Anonymous");
                payload.put("address",      "1 Duong Test So " + suffix + ", Test City");
                payload.put("customerType", 1);    // Cá nhân
                payload.put("profileType",  2);    // Nước ngoài (individual)
                payload.put("titleId",      1);    // Ông
                payload.put("identifyId",   randNumericId(10, 12));  // CCCD/passport
                payload.put("passportDate", "2024-01-01T00:00:00");
                payload.put("issuedBy",     "Cuc Quan ly xuat nhap canh");
                payload.put("countryCode",  "VNM");
            }

            case KIND_ORG_DOMESTIC -> {
                // ── Tổ chức trong nước (customerType=2, profileType=3) ────────────────
                // Required extra: cityId, wardId, streetName, contacts[contactType="2",position].
                // taxNo = MST 10 digits (sent directly → overrides any auto-gen).
                customerName = namePrefix + " " + suffix;
                String slug  = customerName.toLowerCase().replaceAll("[^a-z0-9]+", "");
                String taxNo = randNumericId(10, 10);  // MST domestic = exactly 10 digits
                // Đại diện pháp luật contact: contactType="2", position required
                Map<String, Object> legalRep = new LinkedHashMap<>();
                legalRep.put("contactName",  "Nguyen Van Dai Dien");
                legalRep.put("contactType",  "2");       // 2 = Đại diện pháp luật (confirmed live)
                legalRep.put("position",     "Giam doc");
                legalRep.put("phoneNumbers", List.of(phone));
                payload.put("customerName", customerName);
                payload.put("email",        slug + "@example-test.vn");
                payload.put("phone",        phone);
                payload.put("groupCode",    "Anonymous");
                payload.put("address",      suffix + " Duong Test, Ha Noi");
                payload.put("customerType", 2);    // Tổ chức
                payload.put("profileType",  3);    // Doanh nghiệp trong nước
                payload.put("taxNo",        taxNo);
                payload.put("cityId",       DOMESTIC_CITY_ID);  // Hà Nội
                payload.put("wardId",       DOMESTIC_WARD_ID);  // Xã Vĩnh Thanh
                payload.put("streetName",   suffix + " Duong Test");
                payload.put("contacts",     List.of(legalRep));
            }

            case KIND_IND_DOMESTIC -> {
                // ── Cá nhân trong nước (customerType=1, profileType=1) ────────────────
                // Required extra: identifyId (12-digit CCCD), passportDate (issued date),
                // issuedBy, cityId, wardId, streetName.
                customerName = randomPersonName();
                String slug  = (customerName + suffix).toLowerCase().replaceAll("[^a-z0-9]+", "");
                payload.put("customerName", customerName);
                payload.put("email",        slug + "@example-test.vn");
                payload.put("phone",        phone);
                payload.put("groupCode",    "Anonymous");
                payload.put("address",      suffix + " Duong Test, Ha Noi");
                payload.put("customerType", 1);    // Cá nhân
                payload.put("profileType",  1);    // Trong nước
                payload.put("titleId",      1);    // Ông
                payload.put("identifyId",   randNumericId(12, 12));  // CCCD = exactly 12 digits
                payload.put("passportDate", "2021-07-01T00:00:00");
                payload.put("issuedBy",     "CA HA NOI");
                payload.put("cityId",       DOMESTIC_CITY_ID);  // Hà Nội
                payload.put("wardId",       DOMESTIC_WARD_ID);  // Xã Vĩnh Thanh
                payload.put("streetName",   suffix + " Duong Test");
            }

            default -> {
                // ── Tổ chức nước ngoài (customerType=2, profileType=4) ───────────────
                // Simplest: no province/ward. taxNo overrides auto-generated FTINN* value.
                // NOTE: identifyId is the CCCD/passport field (not MST) — omit for org type.
                // MST for org is set via taxNo field directly.
                customerName = namePrefix + " " + suffix;
                String slug  = customerName.toLowerCase().replaceAll("[^a-z0-9]+", "");
                payload.put("customerName", customerName);
                payload.put("email",        slug + "@example-test.vn");
                payload.put("phone",        phone);
                payload.put("groupCode",    "Anonymous");
                payload.put("address",      "1 Duong Test So " + suffix + ", Test City");
                payload.put("customerType", 2);    // Tổ chức nước ngoài
                payload.put("profileType",  4);
                payload.put("taxNo",        randNumericId(10, 10));  // MST tổ chức VN = đúng 10 chữ số
            }
        }

        long t0 = System.currentTimeMillis();
        List<String> log = new ArrayList<>();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS)).build();

        trace(log, "POST " + CREATE_URL + " kind=" + kind + " customerName=" + customerName);
        JsonNode response = post(client, CREATE_URL, cmToken, toJson(payload), log);

        String customerCode = response != null ? response.path("data").path("customerCode").asText(null) : null;
        if (customerCode == null) {
            throw new GeneratorException(
                    "Tạo khách hàng thành công nhưng không đọc được customerCode từ response: " + response);
        }

        return result()
                .put("customerCode", customerCode)
                .put("customerName", customerName)
                .put("customerKind", kind)
                .put("taxNo",        response.path("data").path("taxNo").asText(null))
                .log(log)
                .durationMs(System.currentTimeMillis() - t0)
                .build();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private String randomPersonName() {
        String surname = SURNAMES[ThreadLocalRandom.current().nextInt(SURNAMES.length)];
        String given   = GIVEN_NAMES[ThreadLocalRandom.current().nextInt(GIVEN_NAMES.length)];
        String given2  = GIVEN_NAMES[ThreadLocalRandom.current().nextInt(GIVEN_NAMES.length)];
        return surname + " Van " + given + " " + given2;  // 4-word name, ~12×15×15 combos
    }

    private JsonNode post(HttpClient client, String url, String token, String body, List<String> log) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header("Accept", "application/json, text/plain, */*")
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            trace(log, "network error: " + e.getMessage());
            throw new GeneratorException("Could not reach " + url, HttpStatus.BAD_GATEWAY, e);
        }
        trace(log, "-> HTTP " + response.statusCode());
        JsonNode parsed = parseQuietly(response.body());
        if (response.statusCode() / 100 != 2
                || (parsed != null && "NOTOK".equalsIgnoreCase(parsed.path("status").asText("")))) {
            trace(log, "response: " + response.body());
            HttpStatus status = HttpStatus.resolve(response.statusCode());
            throw new GeneratorException(
                    "CM API rejected the request (HTTP " + response.statusCode() + "): " + response.body(),
                    status != null ? status : HttpStatus.BAD_GATEWAY, null);
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

    /** Sinh chuỗi số nguyên dương thuần túy, độ dài ngẫu nhiên trong [minLen, maxLen]. */
    private static String randNumericId(int minLen, int maxLen) {
        int len = minLen + (maxLen > minLen
                ? ThreadLocalRandom.current().nextInt(maxLen - minLen + 1) : 0);
        StringBuilder sb = new StringBuilder(len);
        sb.append((char) ('1' + ThreadLocalRandom.current().nextInt(9))); // chữ số đầu 1–9
        for (int i = 1; i < len; i++) {
            sb.append((char) ('0' + ThreadLocalRandom.current().nextInt(10)));
        }
        return sb.toString();
    }
}
