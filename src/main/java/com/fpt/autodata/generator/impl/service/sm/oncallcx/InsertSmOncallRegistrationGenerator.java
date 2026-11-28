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

        // ── Step 1: SM SD API — POST /sm/v2/sd/api/registrations ────────────────
        // Payload captured live 2026-11-28 từ browser network khi user thao tác thủ công
        // trên sm/v2/sd/deploys/create cho CH00086729 — đây là exact body cấu trúc đúng.
        long totalMoney = (long)(packPrice * qty);
        String saleUsername = saleName.contains("@") ? saleName.split("@")[0] : saleName;

        ObjectNode smRegistration = MAPPER.createObjectNode();
        smRegistration.put("id", 0);
        smRegistration.put("objId", 0);
        smRegistration.put("taxId", taxId != null ? taxId : "");
        smRegistration.put("opportunityCode", opportunityId);
        smRegistration.putNull("contractCode");
        smRegistration.put("refCode", refCode);
        smRegistration.put("serviceGroup", SERVICE_GROUP);
        smRegistration.put("serviceType", SERVICE_TYPE);
        smRegistration.put("status", 0);
        smRegistration.put("attachFile", "[]");
        smRegistration.put("attachFileMaterial", "[]");
        smRegistration.putNull("note");
        smRegistration.put("initServiceCharge", 0);
        smRegistration.put("discount", 0);
        smRegistration.put("discountType", 2);
        smRegistration.put("totalMoney", totalMoney);
        smRegistration.put("discountProduct", 0);
        smRegistration.put("discountTypeProduct", 2);
        smRegistration.put("totalMoneyProduct", 0);
        smRegistration.put("vat", vat);
        smRegistration.putNull("description");
        smRegistration.put("isTrialContract", 0);
        smRegistration.put("createUser", saleUsername);
        smRegistration.put("requestType", 1);

        // serviceInfor: thông số kỹ thuật OnCallCX (email CRM, modem, v.v.)
        ObjectNode dataValue = MAPPER.createObjectNode();
        dataValue.put("configDate", "");
        dataValue.put("userName", emailCrmCx);
        dataValue.put("company", "");
        dataValue.put("password", "");
        dataValue.put("recodingFileStogeTime", "45");
        dataValue.put("voiceMailStogeTime", "45");
        dataValue.put("channelModem", modemInfo);
        dataValue.put("initType", "1");
        ObjectNode serviceInfor = MAPPER.createObjectNode();
        serviceInfor.put("id", 0);
        serviceInfor.set("dataValue", dataValue);

        // phoneAttaches: đầu số iVoice đính kèm
        ObjectNode phoneAttach = MAPPER.createObjectNode();
        phoneAttach.put("id", 0);
        phoneAttach.put("provider", 1);
        phoneAttach.put("phoneNumber", ivoicePhoneNumber);
        phoneAttach.put("contractIvoice", ivoiceContract);
        phoneAttach.put("ipHost", "1.1.11.1");
        phoneAttach.put("port", "80");
        phoneAttach.put("callIOFormat", "2");
        phoneAttach.put("extensionInfor", "");
        phoneAttach.put("userCRMEmail", emailCrmCx);
        phoneAttach.put("userCRMRole", 2);
        phoneAttach.put("deployType", 0);

        // dataDetails: gói dịch vụ — nested {dataDetail:{...}, serviceDetails:[]}
        ObjectNode dataDetailInner = MAPPER.createObjectNode();
        dataDetailInner.put("id", 0);
        dataDetailInner.put("type", 1);
        dataDetailInner.put("packageServiceId", packId);
        dataDetailInner.put("serviceGroup", SERVICE_GROUP);
        dataDetailInner.put("serviceType", SERVICE_TYPE);
        dataDetailInner.put("unitPrice", (long) packPrice);
        dataDetailInner.put("quantity", qty);
        dataDetailInner.put("packageIdentifier", "1");
        dataDetailInner.putNull("description");
        dataDetailInner.putNull("note");
        dataDetailInner.put("provinceId", locationId);
        dataDetailInner.put("districtId", districtId > 0 ? districtId : 1000009697);
        dataDetailInner.put("wardId", districtId > 0 ? districtId : 1000009697);
        dataDetailInner.put("address", "22");
        dataDetailInner.put("addressFull", "22, Phường An Đông, Thành phố Hồ Chí Minh");
        dataDetailInner.put("provinceName", "Thành phố Hồ Chí Minh");
        dataDetailInner.put("districtName", "Phường An Đông");
        dataDetailInner.put("wardName", "Phường An Đông");
        dataDetailInner.put("contactName", billingName);
        dataDetailInner.put("contactPhone", billingPhone);
        dataDetailInner.put("contactEmail", billingEmail != null && !billingEmail.isBlank() ? billingEmail : emailCrmCx);
        dataDetailInner.put("status", 0);
        dataDetailInner.put("name", packName);
        dataDetailInner.put("packageServiceDetail", "");
        dataDetailInner.put("licenseSize", 0);
        dataDetailInner.put("isLadderPrice", 0);
        dataDetailInner.put("isManualAmount", false);
        dataDetailInner.put("monthUse", months);
        dataDetailInner.put("intoMoney", totalMoney);
        dataDetailInner.put("monthUser", months);
        dataDetailInner.put("isAddOn", 0);
        dataDetailInner.put("isTrial", 0);
        dataDetailInner.put("prepaidMonth", 0);
        ObjectNode dataDetailWrapper = MAPPER.createObjectNode();
        dataDetailWrapper.set("dataDetail", dataDetailInner);
        dataDetailWrapper.set("serviceDetails", MAPPER.createArrayNode());

        ObjectNode smBody = MAPPER.createObjectNode();
        smBody.set("registration", smRegistration);
        smBody.set("serviceInfor", serviceInfor);
        smBody.set("materials", MAPPER.createArrayNode());
        smBody.set("phoneAttaches", MAPPER.createArrayNode().add(phoneAttach));
        smBody.set("dataDetails", MAPPER.createArrayNode().add(dataDetailWrapper));

        String smResult = "unknown";
        String smEndpointUsed = SM_SD_BASE + "/registrations";
        boolean smOk = false;

        trace(log, "POST " + smEndpointUsed + " opportunityCode=" + opportunityId + " packId=" + packId);
        try {
            JsonNode smResp = postJson(client, smEndpointUsed, smToken, toJson(smBody), log);
            smResult = smResp != null ? smResp.toString() : "{}";
            // Check success field
            boolean smSuccess = smResp != null && smResp.path("success").asBoolean(false);
            if (smSuccess) {
                smOk = true;
                trace(log, "SM SD POST /registrations OK");
            } else {
                String smMsg = smResp != null ? smResp.path("error").path("message").asText("?") : "?";
                trace(log, "SM SD POST /registrations NOTOK: " + smMsg + " — tiếp tục BMS");
                smResult = "sm-failed: " + smMsg;
                smOk = false; // Log nhưng không throw — vẫn tiếp tục BMS
            }
        } catch (GeneratorException e) {
            trace(log, "SM SD POST lỗi: " + (e.getMessage() != null ? e.getMessage().substring(0, Math.min(200, e.getMessage().length())) : "?") + " — tiếp tục BMS");
            smResult = "sm-exception";
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
