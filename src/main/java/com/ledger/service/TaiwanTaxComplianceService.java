package com.ledger.service;

import com.ledger.exception.BusinessException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Pattern;

/**
 * 台灣稅務合規計算與檢核服務（內部計算與檢核核心）。
 * <p>
 * 重要聲明：本類別僅提供內部稅額試算與憑證格式檢核，尚未串接財政部電子發票整合服務平台
 * （Turnkey / B2B / B2C XML）及國稅局網路申報（營業稅 401、營所稅、各類所得扣繳媒體申報）API。
 */
public class TaiwanTaxComplianceService {

    public static final String SYSTEM_POSITIONING_NOTICE =
            "本系統定位為會計記帳與內部稅務試算核心模組（支援營業稅 5%、營利事業所得稅 20% 含起徵額與半數課稅規定、" +
            "各類所得扣繳試算及統一發票字軌格式檢核）；尚未串接財政部電子發票整合服務平台（Turnkey）及國稅局網路申報 API，" +
            "正式申報仍須匯出媒體檔由外部申報系統辦理。";

    private static final Pattern UNIFORM_INVOICE_PATTERN = Pattern.compile("^[A-Z]{2}-?\\d{8}$");
    private static final BigDecimal VAT_RATE = new BigDecimal("0.05");
    private static final BigDecimal ONE_PLUS_VAT = new BigDecimal("1.05");
    private static final long CIT_EXEMPTION_MINOR = 12_000_000L; // 新臺幣 120,000 元 = 12,000,000 分

    public record VatResult(long salesAmountMinor, long vatAmountMinor, long totalAmountMinor) {}

    public record CitResult(long taxableIncomeMinor, long taxPayableMinor, String ruleApplied) {}

    public record WithholdingResult(long grossMinor, long taxWithheldMinor, long netPayMinor,
                                    boolean requiresFilingImmediately) {}

    public boolean isValidUniformInvoiceNo(String invoiceNo) {
        if (invoiceNo == null) return false;
        return UNIFORM_INVOICE_PATTERN.matcher(invoiceNo.trim().toUpperCase()).matches();
    }

    /**
     * 加值型營業稅 5% 試算（單位：分）。
     */
    public VatResult calculateVat(long amountMinor, boolean isTaxInclusive) {
        if (amountMinor < 0) {
            throw new BusinessException("營業稅計算金額不可為負");
        }
        if (isTaxInclusive) {
            long sales = BigDecimal.valueOf(amountMinor)
                    .divide(ONE_PLUS_VAT, 0, RoundingMode.HALF_UP)
                    .longValueExact();
            long vat = amountMinor - sales;
            return new VatResult(sales, vat, amountMinor);
        } else {
            long vat = BigDecimal.valueOf(amountMinor)
                    .multiply(VAT_RATE)
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact();
            return new VatResult(amountMinor, vat, amountMinor + vat);
        }
    }

    /**
     * 營利事業所得稅試算（所得稅法第 5 條第 5 項）：
     * - 課稅所得額 <= 120,000 元：免徵
     * - 超過 120,000 元：稅率 20%，惟應納稅額不得超過超過 120,000 元部分之半數（200,000 元以下漸進過渡區）
     */
    public CitResult calculateCorporateIncomeTax(long annualTaxableIncomeMinor) {
        if (annualTaxableIncomeMinor <= CIT_EXEMPTION_MINOR) {
            return new CitResult(annualTaxableIncomeMinor, 0L, "免稅（未逾新臺幣 120,000 元起徵額）");
        }
        long fullTwentyPercent = BigDecimal.valueOf(annualTaxableIncomeMinor)
                .multiply(new BigDecimal("0.20"))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
        long halfExcessCap = (annualTaxableIncomeMinor - CIT_EXEMPTION_MINOR) / 2;
        if (halfExcessCap < fullTwentyPercent) {
            return new CitResult(annualTaxableIncomeMinor, halfExcessCap,
                    "適用半數課稅上限規定（應納稅額不得超過逾 120,000 元部分之 50%）");
        }
        return new CitResult(annualTaxableIncomeMinor, fullTwentyPercent, "適用標準稅率 20%");
    }

    /**
     * 各類所得扣繳稅額試算（依各類所得扣繳率標準常見項目）。
     * category: "SALARY" (5% 居住者 / 18% 非居住者), "RENT" or "PROFESSIONAL" (10% 居住者 / 20% 非居住者)。
     */
    public WithholdingResult calculateWithholding(long grossPayMinor, String incomeCategory, boolean isResident) {
        if (grossPayMinor < 0) {
            throw new BusinessException("給付總額不可為負");
        }
        String cat = incomeCategory == null ? "" : incomeCategory.trim().toUpperCase();
        BigDecimal rate;
        switch (cat) {
            case "SALARY" -> rate = isResident ? new BigDecimal("0.05") : new BigDecimal("0.18");
            case "RENT", "PROFESSIONAL" -> rate = isResident ? new BigDecimal("0.10") : new BigDecimal("0.20");
            default -> throw new BusinessException("不支援的扣繳所得類別: " + incomeCategory);
        }
        long tax = BigDecimal.valueOf(grossPayMinor)
                .multiply(rate)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
        // 居住者每次應扣繳稅額不超過 2,000 元（200,000 分）免予扣繳
        if (isResident && tax <= 200_000L) {
            tax = 0L;
        }
        return new WithholdingResult(grossPayMinor, tax, grossPayMinor - tax, !isResident);
    }
}
