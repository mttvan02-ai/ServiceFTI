package com.fpt.autodata.generator.impl.service.sm.oncallcx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tạo Yêu cầu Hợp đồng (YCHD) cho dịch vụ OnCallCX — Tổng đài
 * (serviceGroup=13000, serviceType=13007) qua SM SD v2 + BMS v2 APIs.
 *
 * <p>Flow:
 * <ol>
 *   <li>POST {SM_SD_BASE}/registrations/insert-registration — SM SD API tạo registration</li>
 *   <li>POST {bmsBase}/registration/{registrationId} — BMS v2 finalize</li>
 * </ol>
 * </p>
 *
 * <p>Auth: BMS Bearer token (auth.get-token-api) + SM Bearer token (auth.get-sm-token-api).</p>
 *
 * <p>Capture live 2026-11-27: form OnCallCX dùng SM SD API (không phải ivoice API),
 * endpoint /registrations/insert-registration (không phải insert-registration-iframe).</p>
 */
@Component
public class InsertSmOncallRegistrationGenerator implements Generator {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SM_SD_BASE = "https://ftisapi-stag.fpt.net/sm/v2/sd/api";
    private static final int TIMEOUT_SECONDS = 30;
    private static final int SERVICE_GROUP = 13000;
    private static final int SERVICE_TYPE  = 13007; // OnCallCX

    private final AutodataProperties properties;

    public InsertSmOncallRegistrationGenerator(AutodataProperties properties) {
        this.properties = properties;
    }

    @Override public String type()        { return "sm.oncallcx.insert-registration"; }
    @Override public String displayName() { return "Tạo YCHD — OnCallCX Tổng đài (SM SD v2 + BMS v2)"; }
    @Override public String category()    { return "SM"; }
    @Override public String description() {
        return "POST /sm/v2/sd/api/registrations/insert-registration (SM SD) → POST {bmsBase}/registration/{id} (BMS v2). serviceGroup=13000 serviceType=13007 (OnCallCX).";
    }
    @Override public String signature() {
        return "POST {SM_SD_BASE}/registrations/insert-registration, POST {bmsBase}/registration/{registrationId}";
    }

    @Override
    public List<ParamSpec> params() {
        return List.of(
                // Auth
                ParamSpec.password("bmsToken").label("BMS Bearer token").required()
                        .hint("lấy qua generator auth.get-token-api"),
                ParamSpec.password("smToken").label("SM Bearer token").required()
                        .hint("lấy qua generator auth.get-sm-token-api — token khác BMS"),

                // Opportunity
                ParamSpec.text("opportunityId").label("Mã cơ hội").required().placeholder("CH00086724"),
                ParamSpec.number("registrationId").label("Registration ID").required()
                        .hint("số sau dấu _ trong RefCode, vd CH00086724_100847 → 100847"),
                ParamSpec.text("taxId").label("Mã số thuế KH").required().placeholder("0300608064"),
                ParamSpec.number("serviceId").label("Service ID (BMS)").required()
                        .hint("vd 101345 — lấy từ GET /registration/{id}.data.serviceId"),
                ParamSpec.text("saleName").label("Sale username (BMS)").required()
                        .placeholder("thitlh"),

                // Gói dịch vụ OnCallCX
                ParamSpec.number("packId").label("ID gói dịch vụ (packId)").required()
                        .hint("lấy từ sm.service-packs"),
                ParamSpec.text("packName").label("Tên gói dịch vụ").required()
                        .placeholder("ONCALLCX_BASIC"),
                ParamSpec.number("packPrice").label("Giá gói (đ/tháng)").defaultValue(0),
                ParamSpec.number("qty").label("Số lượng gói").defaultValue(1),
                ParamSpec.number("months").label("Số tháng sử dụng").defaultValue(1),
                ParamSpec.number("vat").label("VAT (%)").defaultValue(10),

                // OnCallCX specific
                ParamSpec.text("emailCrmCx").label("Email user CRM/CX").required()
                        .hint("đã validate qua sm.oncall-validate-email"),
                ParamSpec.text("modemInfo").label("Thông tin modem (site KHG)").defaultValue("KH-stg"),
                ParamSpec.text("ivoiceContract").label("Số HĐ Voice đính kèm").required()
                        .hint("vd SGV018050 — HĐ iVoice còn đầu số của KH"),
                ParamSpec.text("ivoicePhoneNumber").label("Đầu số iVoice đính kèm").required()
                        .hint("lấy từ sm.oncall-phone-by-contract"),

                // BMS contact fields
                ParamSpec.number("locationId").label("Tỉnh/Thành (locationId)").defaultValue(8)
                        .hint("8 = HCM, 4 = HN"),
                ParamSpec.text("billingName").label("Tên liên hệ thanh toán").defaultValue("qc"),
                ParamSpec.text("billingEmail").label("Email thanh toán").required().placeholder("vanmtt3@fpt.com"),
                ParamSpec.text("billingPhone").label("SĐT thanh toán").defaultValue("0345844589"),
                ParamSpec.number("districtId").label("District ID (địa chỉ thanh toán)").defaultValue(1000009712),
                ParamSpec.text("techName").label("Tên liên hệ kỹ thuật").defaultValue("qc"),
                ParamSpec.text("techEmail").label("Email kỹ thuật").required().placeholder("vanmtt3@fpt.com"),
                ParamSpec.text("techPhone").label("SĐT kỹ thuật").defaultValue("0345855458"),

                // Tài chính HĐ (BMS)
                ParamSpec.number("feeContract").label("Cước trả một lần (VNĐ)").defaultValue(0),
                ParamSpec.number("monthlyFeeContract").label("Cước tháng (VNĐ)").defaultValue(0),
                ParamSpec.number("dateContractMonth").label("Số tháng sử dụng HĐ").defaultValue(1),
                ParamSpec.number("limitCreditDay").label("Hạn mức công nợ (ngày)").defaultValue(30),
                ParamSpec.text("effectiveDate").label("Ngày hiệu lực HĐ (yyyy-MM-dd)")
                        .defaultValue(LocalDate.now().minusDays(1).toString())
        );
    }

    @Override
    public GenerationResult generate(Map<String, Object> p) {
        String bmsToken = Params.requireString(p, "bmsToken");
        String smToken  = Params.requireString(p, "smToken");
        String bmsBase  = properties.getApi().getBaseUrl().replaceAll("/$", "");

        String opportunityId  = Params.requireString(p, "opportunityId");
        int    registrationId = (int) Params.optInt(p, "registrationId", 0);
        if (registrationId == 0) throw new GeneratorException("registrationId là bắt buộc");
        String taxId     = Params.requireString(p, "taxId");
        int    serviceId = (int) Params.optInt(p, "serviceId", 0);
        if (serviceId == 0) throw new GeneratorException("serviceId là bắt buộc");
        String saleName  = Params.requireString(p, "saleName");
        String refCode   = opportunityId + "_" + registrationId;

        int    packId    = (int) Params.optInt(p, "packId", 0);
        if (packId == 0) throw new GeneratorException("packId là bắt buộc");
        String packName  = Params.requireString(p, "packName");
        long   packPrice = Params.optInt(p, "packPrice", 0);
        int    qty       = (int) Params.optInt(p, "qty", 1);
        int    months    = (int) Params.optInt(p, "months", 1);
        int    vat       = (int) Params.optInt(p, "vat", 10);

        String emailCrmCx       = Params.requireString(p, "emailCrmCx");
        String modemInfo        = Params.optString(p, "modemInfo", "KH-stg");
        String ivoiceContract   = Params.requireString(p, "ivoiceContract");
        String ivoicePhoneNumber = Params.requireString(p, "ivoicePhoneNumber");

        int    locationId   = (int) Params.optInt(p, "locationId", 8);
        String billingName  = Params.optString(p, "billingName", "qc");
        String billingEmail = Params.requireString(p, "billingEmail");
        String billingPhone = Params.optString(p, "billingPhone", "0345844589");
        long   districtId   = Params.optInt(p, "districtId", 1000009712);
        String techName     = Params.optString(p, "techName", "qc");
        String techEmail    = Params.requireString(p, "techEmail");
        String techPhone    = Params.optString(p, "techPhone", "0345855458");

        long   feeContract       = Params.optInt(p, "feeContract", 0);
        long   monthlyFee        = Params.optInt(p, "monthlyFeeContract", 0);
        int    dateContractMonth = (int) Params.optInt(p, "dateContractMonth", 1);
        int    limitCreditDay    = (int) Params.optInt(p, "limitCreditDay", 30);
        String effectiveDate     = Params.optString(p, "effectiveDate", LocalDate.now().minusDays(1).toString());

        long t0 = System.currentTimeMillis();
        List<String> log = new ArrayList<>();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS)).build();

        // ── Step 1: SM SD API — POST /registrations/insert-registration ──────────
        ObjectNode configInfo = MAPPER.createObjectNode();
        configInfo.put("emailUserCrmCx", emailCrmCx);
        configInfo.put("modemInfo", modemInfo);
        configInfo.put("ivoiceContract", ivoiceContract);
        configInfo.put("ivoicePhoneNumber", ivoicePhoneNumber);

        ObjectNode regDetail = MAPPER.createObjectNode();
        regDetail.put("id", 1);
        regDetail.put("isNew", 1);
        regDetail.put("regDetailCode", opportunityId + "-1");
        regDetail.put("serviceGroup", SERVICE_GROUP);
        regDetail.put("serviceType", SERVICE_TYPE);
        regDetail.put("packId", packId);
        regDetail.put("packName", packName);
        regDetail.put("packPrice", packPrice);
        regDetail.put("qty", qty);
        regDetail.put("months", months);
        regDetail.put("vat", vat);
        regDetail.set("configInfo", configInfo);

        ObjectNode smBody = MAPPER.createObjectNode();
        smBody.put("id", 0);
        smBody.put("opportunityCode", opportunityId);
        smBody.put("refCode", refCode);
        smBody.put("serviceGroup", SERVICE_GROUP);
        smBody.put("serviceType", SERVICE_TYPE);
        smBody.put("taxId", taxId);
        smBody.put("emailUserCrmCx", emailCrmCx);
        smBody.put("modemInfo", modemInfo);
        smBody.put("ivoiceContract", ivoiceContract);
        smBody.put("ivoicePhoneNumber", ivoicePhoneNumber);
        smBody.set("registrationDetails", MAPPER.createArrayNode().add(regDetail));

        // Try primary endpoint first; if 404/405/500 try alternatives
        String smResult = "unknown";
        String[] smEndpoints = {
                SM_SD_BASE + "/registrations/insert-registration",
                SM_SD_BASE + "/registrations/insert-registration-iframe",
                SM_SD_BASE + "/registration/insert-registration",
                SM_SD_BASE + "/registration/insert-registration-iframe",
        };

        boolean smOk = false;
        String smEndpointUsed = smEndpoints[0];
        for (String endpoint : smEndpoints) {
            trace(log, "POST " + endpoint + " refCode=" + refCode);
            try {
                JsonNode smResp = postJson(client, endpoint, smToken, toJson(smBody), log);
                smResult = smResp != null ? smResp.toString() : "{}";
                smEndpointUsed = endpoint;
                smOk = true;
                trace(log, "SM SD step 1 OK via " + endpoint);
                break;
            } catch (GeneratorException e) {
                String msg = e.getMessage() != null ? e.getMessage() : "";
                // If 404/405/501 → try next endpoint
                if (msg.contains("HTTP 404") || msg.contains("HTTP 405") || msg.contains("HTTP 501")) {
                    trace(log, "Endpoint " + endpoint + " không khả dụng (" + msg.substring(0, Math.min(100, msg.length())) + ") — thử endpoint tiếp theo");
                } else if (msg.contains("đang trong quy trình") || msg.contains("BUSINESS_ERROR") || msg.contains("already")) {
                    // Business error but SM accepted the request → continue to BMS
                    trace(log, "SM SD: " + msg.substring(0, Math.min(200, msg.length())));
                    smResult = "business-error-skip";
                    smEndpointUsed = endpoint;
                    smOk = true;
                    break;
                } else {
                    // Unexpected error — log and rethrow
                    trace(log, "SM SD lỗi: " + msg);
                    throw e;
                }
            }
        }

        if (!smOk) {
            trace(log, "WARN: Tất cả SM SD endpoints đều trả 404/405 — tiếp tục BMS step (SM có thể không yêu cầu bước này)");
            smResult = "all-endpoints-404";
        }

        // ── Step 2: BMS v2 — POST /registration/{registrationId} ─────────────────
        // Pattern copy từ InsertCskhRegistrationGenerator (serviceGroup/Type đổi sang 13000/13007)
        String effectiveDatetime = effectiveDate + "T00:00:00";
        long contractValue = feeContract + monthlyFee;

        ObjectNode registration = MAPPER.createObjectNode();
        registration.put("typeDocContract", 1);
        registration.put("paymentMethod", 2);
        registration.put("location", locationId);
        registration.put("typeContract", 1);
        registration.putNull("eContractFlag");
        registration.putNull("agencyContractFlag");
        registration.putNull("onSiteWarranty");
        registration.putNull("autoRenewContract");
        registration.putNull("isDebt");
        registration.put("feeContract", feeContract);
        registration.put("monthlyFeeContract", monthlyFee);
        registration.put("contractValue", contractValue);
        registration.put("dateContractMonth", dateContractMonth);
        registration.put("paymentTerm", 1);
        registration.put("effectiveContractDate", effectiveDatetime);
        registration.put("limitCreditDay", limitCreditDay);
        registration.putNull("notes");
        registration.putNull("description");
        registration.putNull("debtLimitDayCount");
        registration.putNull("debtApprovalEmail");
        registration.putNull("debtUpdateDate");
        registration.putNull("debtDateLimit");
        registration.putNull("startContractDate");
        registration.putNull("endContractDate");
        registration.put("contractValueCurrency", "VND");
        registration.putNull("status");
        registration.put("saleName", saleName);
        registration.putNull("contactName");
        registration.putNull("contactEmail");
        registration.putNull("contactPhone");
        registration.putNull("billingAddress");
        registration.putNull("contactDeployName");
        registration.putNull("contactDeployEmail");
        registration.putNull("contactDeployPhone");
        registration.putNull("preSigner");
        registration.putNull("ftiRepresentative");
        registration.putNull("subContractTemp");
        registration.putNull("emailCusToReceiveContract");
        registration.putNull("ftiLegal");
        registration.putNull("techContact");
        registration.putNull("techEmail");
        registration.putNull("techNumber");
        registration.putNull("subContractNo");
        registration.put("serviceId", serviceId);
        registration.put("contractNo", "");
        registration.set("packServiceIds", MAPPER.createArrayNode());

        ObjectNode billingContact = MAPPER.createObjectNode();
        billingContact.put("contactName", billingName);
        billingContact.put("contactEmail", billingEmail);
        billingContact.put("contactPhone", billingPhone);
        billingContact.put("location", locationId);
        billingContact.putNull("locationName");
        billingContact.put("district", districtId);
        billingContact.putNull("districtName");
        billingContact.putNull("ward");
        billingContact.putNull("wardName");
        billingContact.put("address", "22 Nguyễn Văn A");
        billingContact.put("country", "vn");

        ObjectNode techContact = MAPPER.createObjectNode();
        techContact.put("name", techName);
        techContact.put("email", techEmail);
        techContact.put("phone", techPhone);

        ObjectNode deployAddress = MAPPER.createObjectNode();
        deployAddress.put("address", "22 Nguyễn Văn A");
        deployAddress.put("fullAddress", "22 Nguyễn Văn A, Thành phố Hồ Chí Minh");
        deployAddress.put("location", locationId);
        deployAddress.put("district", districtId);
        deployAddress.put("contactName", saleName);
        deployAddress.put("contactEmail", billingEmail);
        deployAddress.put("contactPhone", billingPhone);
        deployAddress.put("serviceGroup", SERVICE_GROUP);
        deployAddress.put("serviceType", SERVICE_TYPE);
        deployAddress.putNull("refCodeIntegrated");
        deployAddress.put("isAddressOfIntegrated", true);
        deployAddress.put("isSelected", true);

        ObjectNode memberRow = MAPPER.createObjectNode();
        memberRow.put("name", saleName);
        memberRow.put("position", 1);
        memberRow.put("contractRate", 100);

        ObjectNode bmsBody = MAPPER.createObjectNode();
        bmsBody.set("registration", registration);
        bmsBody.set("listBillingContact", MAPPER.createArrayNode().add(billingContact));
        bmsBody.set("listTechContact", MAPPER.createArrayNode().add(techContact));
        bmsBody.set("listDeployAddress", MAPPER.createArrayNode().add(deployAddress));
        bmsBody.put("isUpdateTemp", false);
        bmsBody.put("appCodeInsert", "FTIBMS");
        bmsBody.put("opportunityId", opportunityId);
        bmsBody.set("listEContractDocument", MAPPER.createArrayNode());
        bmsBody.set("listCooperationAgreementDocument", MAPPER.createArrayNode());
        bmsBody.set("listMember", MAPPER.createArrayNode().add(memberRow));
        bmsBody.set("listServiceTypeOfPack", MAPPER.createArrayNode());

        String bmsUrl = bmsBase + "/registration/" + registrationId;
        trace(log, "POST " + bmsUrl + " opportunityId=" + opportunityId + " isUpdateTemp=false");
        String bmsStatus = "Chờ BGĐ duyệt";
        try {
            postJson(client, bmsUrl, bmsToken, toJson(bmsBody), log);
        } catch (GeneratorException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("đang trong quy trình") || msg.contains("BUSINESS_ERROR")) {
                trace(log, "BMS skip: " + msg.substring(0, Math.min(120, msg.length())));
                bmsStatus = "Đã tồn tại (skip BMS) — tiếp tục BGĐ/CM";
            } else {
                throw e;
            }
        }

        return GenerationResult.builder(type())
                .put("refCode", refCode)
                .put("opportunityId", opportunityId)
                .put("registrationId", registrationId)
                .put("packId", packId)
                .put("packName", packName)
                .put("ivoicePhoneNumber", ivoicePhoneNumber)
                .put("ivoiceContract", ivoiceContract)
                .put("smEndpointUsed", smEndpointUsed)
                .put("smResult", smResult)
                .put("status", bmsStatus)
                .log(log)
                .durationMs(System.currentTimeMillis() - t0)
                .build();
    }

    private JsonNode postJson(HttpClient client, String url, String bearerToken, String body, List<String> log) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header("Accept", "application/json, text/plain, */*")
                .header("Authorization", "Bearer " + bearerToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return send(client, request, log);
    }

    private JsonNode send(HttpClient client, HttpRequest request, List<String> log) {
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            trace(log, "network error: " + e.getMessage());
            throw new GeneratorException("Không kết nối được " + request.uri(), HttpStatus.BAD_GATEWAY, e);
        }
        int statusCode = response.statusCode();
        trace(log, "-> HTTP " + statusCode + " body=" + response.body().substring(0, Math.min(300, response.body().length())));
        JsonNode parsed = parseQuietly(response.body());
        if (statusCode / 100 != 2) {
            HttpStatus st = HttpStatus.resolve(statusCode);
            throw new GeneratorException(
                    "API trả HTTP " + statusCode + ": " + response.body(),
                    st != null ? st : HttpStatus.BAD_GATEWAY, null);
        }
        return parsed;
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

    private void trace(List<String> log, String line) {
        log.add("[" + LocalDateTime.now().format(TS) + "] " + line);
    }
}
