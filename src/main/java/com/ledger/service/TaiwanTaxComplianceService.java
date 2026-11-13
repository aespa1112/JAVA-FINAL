package com.ledger.service;

import com.ledger.exception.BusinessException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 台灣稅務合規計算與檢核服務（內部計算與檢核核心）。
 * <p>
 * 重要定位與免責聲明：
 * 本類別僅提供「內部稅額試算與憑證格式／邏輯檢核」（{@link FilingStatus#INTERNAL_ESTIMATE_ONLY}），
 * 尚未串接財政部電子發票整合服務平台（Turnkey / B2B / B2C XML）及國稅局網路申報
 * （營業稅 401、營利事業所得稅結算申報、各類所得扣繳憑單媒體申報）API。
 * 不得將未連線申報之內部計算宣稱為已完成官方電子申報合規。
 */
public class TaiwanTaxComplianceService {

    public static final String SYSTEM_POSITIONING_NOTICE =
            "本系統定位為會計記帳與內部稅務試算核心模組（支援營業稅應稅 5%/零稅率/免稅與第 19 條不可扣抵進項檢核、" +
            "營利事業所得稅 20% 含 12 萬起徵額與 20 萬半數過渡及十年虧損扣抵試算、各類所得扣繳試算、" +
            "統一編號檢核碼與雙月期別統一發票邏輯檢核）；尚未串接財政部電子發票整合服務平台（Turnkey）及國稅局網路申報 API，" +
            "狀態標示為 INTERNAL_ESTIMATE_ONLY，正式申報仍須匯出申報檔由外部官方申報系統辦理。";

    public enum FilingStatus {
        INTERNAL_ESTIMATE_ONLY
    }

    public enum TaxType {
        TAXABLE_5_PERCENT,
        ZERO_RATED,
        EXEMPT
    }

    public enum InputVatCategory {
        /** 供本業及附屬業務使用之貨物或勞務（可扣抵） */
        DEDUCTIBLE_OPERATING(true, "符合營業稅法規定之可扣抵進項稅額"),
        /** 營業稅法第 19 條第 1 項第 1 款：未依規定取得並保存憑證 */
        NON_DEDUCTIBLE_NO_VALID_DOC(false, "營業稅法第19條第1款：未依規定取得並保存憑證，不得扣抵銷項稅額"),
        /** 營業稅法第 19 條第 1 項第 2 款：非供本業及附屬業務使用之貨物或勞務 */
        NON_DEDUCTIBLE_NON_BUSINESS(false, "營業稅法第19條第2款：非供本業及附屬業務使用，不得扣抵銷項稅額"),
        /** 營業稅法第 19 條第 1 項第 3 款：交際應酬用之貨物或勞務 */
        NON_DEDUCTIBLE_ENTERTAINMENT(false, "營業稅法第19條第3款：交際應酬用貨物或勞務，不得扣抵銷項稅額"),
        /** 營業稅法第 19 條第 1 項第 4 款：酬勞員工個人之貨物或勞務 */
        NON_DEDUCTIBLE_EMPLOYEE_WELFARE(false, "營業稅法第19條第4款：酬勞員工個人之貨物或勞務，不得扣抵銷項稅額"),
        /** 營業稅法第 19 條第 1 項第 5 款：自用乘人小汽車 */
        NON_DEDUCTIBLE_PASSENGER_CAR(false, "營業稅法第19條第5款：自用乘人小汽車，不得扣抵銷項稅額");

        private final boolean deductible;
        private final String legalReason;

        InputVatCategory(boolean deductible, String legalReason) {
            this.deductible = deductible;
            this.legalReason = legalReason;
        }

        public boolean isDeductible() { return deductible; }
        public String getLegalReason() { return legalReason; }
    }

    /** 各主要稅制規則現行法定生效日對照表 */
    public static final Map<String, LocalDate> RULE_EFFECTIVE_DATES = Map.of(
            "VAT_STANDARD_5_PERCENT", LocalDate.of(1986, 4, 1),
            "CIT_20_PERCENT_AND_THRESHOLD", LocalDate.of(2018, 1, 1),
            "LOSS_CARRYFORWARD_10_YEARS", LocalDate.of(2009, 1, 21),
            "GUI_CHECKSUM_MOD_5_STANDARD", LocalDate.of(2023, 4, 1)
    );

    private static final Pattern UNIFORM_INVOICE_PATTERN = Pattern.compile("^[A-Z]{2}-?\\d{8}$");
    private static final Pattern GUI_PATTERN = Pattern.compile("^\\d{8}$");
    private static final int[] GUI_WEIGHTS = {1, 2, 1, 2, 1, 2, 4, 1};

    private static final BigDecimal VAT_RATE = new BigDecimal("0.05");
    private static final BigDecimal ONE_PLUS_VAT = new BigDecimal("1.05");
    private static final long CIT_EXEMPTION_MINOR = 12_000_000L; // 新臺幣 120,000 元 = 12,000,000 分
    private static final long RESIDENT_WITHHOLDING_EXEMPT_MINOR = 200_000L; // 新臺幣 2,000 元 = 200,000 分

    public record VatResult(long salesAmountMinor, long vatAmountMinor, long totalAmountMinor,
                            TaxType taxType, FilingStatus filingStatus) {
        public VatResult(long salesAmountMinor, long vatAmountMinor, long totalAmountMinor) {
            this(salesAmountMinor, vatAmountMinor, totalAmountMinor, TaxType.TAXABLE_5_PERCENT, FilingStatus.INTERNAL_ESTIMATE_ONLY);
        }
    }

    public record VatInputResult(long inputVatMinor, long deductibleVatMinor, long nonDeductibleVatMinor,
                                 InputVatCategory category, String note, FilingStatus filingStatus) {}

    public record VatPeriodResult(long outputVatMinor, long deductibleInputVatMinor, long priorCreditMinor,
                                  long taxPayableMinor, long nextCarryforwardCreditMinor, FilingStatus filingStatus) {}

    public record CitResult(long taxableIncomeMinor, long taxPayableMinor, String ruleApplied, FilingStatus filingStatus) {
        public CitResult(long taxableIncomeMinor, long taxPayableMinor, String ruleApplied) {
            this(taxableIncomeMinor, taxPayableMinor, ruleApplied, FilingStatus.INTERNAL_ESTIMATE_ONLY);
        }
    }

    public record WithholdingResult(long grossMinor, long taxWithheldMinor, long netPayMinor,
                                    boolean requiresFilingImmediately, int filingDeadlineDays, FilingStatus filingStatus) {
        public WithholdingResult(long grossMinor, long taxWithheldMinor, long netPayMinor,
                                 boolean requiresFilingImmediately) {
            this(grossMinor, taxWithheldMinor, netPayMinor, requiresFilingImmediately,
                    requiresFilingImmediately ? 10 : 0, FilingStatus.INTERNAL_ESTIMATE_ONLY);
        }
    }

    public record InvoiceValidationResult(boolean valid, String reason) {}

    public boolean isValidUniformInvoiceNo(String invoiceNo) {
        if (invoiceNo == null) return false;
        return UNIFORM_INVOICE_PATTERN.matcher(invoiceNo.trim().toUpperCase()).matches();
    }

    /**
     * 驗證台灣營利事業統一編號（8 碼官方邏輯檢查碼）。
     */
    public boolean isValidGuiNumber(String gui) {
        if (gui == null || !GUI_PATTERN.matcher(gui.trim()).matches()) {
            return false;
        }
        String s = gui.trim();
        int sum = 0;
        for (int i = 0; i < 8; i++) {
            int digit = s.charAt(i) - '0';
            int prod = digit * GUI_WEIGHTS[i];
            sum += (prod / 10) + (prod % 10);
        }
        if (sum % 5 == 0) {
            return true;
        }
        return s.charAt(6) == '7' && ((sum + 1) % 5 == 0);
    }

    /**
     * 統一發票完整合規檢核（含字軌、期別、應稅/零稅率/免稅與金額邏輯一致性）。
     */
    public InvoiceValidationResult validateUniformInvoice(String invoiceNo, int rocYear, int biMonthlyPeriod,
                                                          long salesMinor, long vatMinor, long totalMinor,
                                                          TaxType taxType) {
        if (!isValidUniformInvoiceNo(invoiceNo)) {
            return new InvoiceValidationResult(false, "統一發票字軌號碼格式無效");
        }
        if (rocYear < 100 || rocYear > 200) {
            return new InvoiceValidationResult(false, "民國年別無效: " + rocYear);
        }
        if (biMonthlyPeriod < 1 || biMonthlyPeriod > 6) {
            return new InvoiceValidationResult(false, "雙月期別無效（須為 1~6 期）: " + biMonthlyPeriod);
        }
        if (salesMinor < 0 || vatMinor < 0 || totalMinor < 0) {
            return new InvoiceValidationResult(false, "發票金額不得為負數");
        }
        if (salesMinor + vatMinor != totalMinor) {
            return new InvoiceValidationResult(false, "發票銷售額 + 稅額與總計不符");
        }
        if (taxType == null) {
            return new InvoiceValidationResult(false, "未指定營業稅課稅別");
        }
        if (taxType == TaxType.ZERO_RATED || taxType == TaxType.EXEMPT) {
            if (vatMinor != 0) {
                return new InvoiceValidationResult(false, "零稅率或免稅發票之稅額必須為 0");
            }
        } else {
            long expectedVat = BigDecimal.valueOf(salesMinor)
                    .multiply(VAT_RATE)
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact();
            if (Math.abs(vatMinor - expectedVat) > 100L) {
                return new InvoiceValidationResult(false, "應稅發票稅額與 5% 計算誤差過大");
            }
        }
        return new InvoiceValidationResult(true, "符合統一發票格式與金額邏輯（內部檢核）");
    }

    /**
     * 加值型營業稅 5% 試算（單位：分）。
     */
    public VatResult calculateVat(long amountMinor, boolean isTaxInclusive) {
        return calculateVat(amountMinor, TaxType.TAXABLE_5_PERCENT, isTaxInclusive);
    }

    /**
     * 加值型營業稅試算（支援應稅 5%、零稅率 0%、免稅）。
     */
    public VatResult calculateVat(long amountMinor, TaxType taxType, boolean isTaxInclusive) {
        if (amountMinor < 0) {
            throw new BusinessException("營業稅計算金額不可為負");
        }
        if (taxType == null) {
            throw new BusinessException("營業稅課稅別不可為空");
        }
        if (taxType == TaxType.ZERO_RATED || taxType == TaxType.EXEMPT) {
            return new VatResult(amountMinor, 0L, amountMinor, taxType, FilingStatus.INTERNAL_ESTIMATE_ONLY);
        }
        if (isTaxInclusive) {
            long sales = BigDecimal.valueOf(amountMinor)
                    .divide(ONE_PLUS_VAT, 0, RoundingMode.HALF_UP)
                    .longValueExact();
            long vat = amountMinor - sales;
            return new VatResult(sales, vat, amountMinor, taxType, FilingStatus.INTERNAL_ESTIMATE_ONLY);
        } else {
            long vat = BigDecimal.valueOf(amountMinor)
                    .multiply(VAT_RATE)
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact();
            return new VatResult(amountMinor, vat, amountMinor + vat, taxType, FilingStatus.INTERNAL_ESTIMATE_ONLY);
        }
    }

    /**
     * 依加值型及非加值型營業稅法第 19 條檢核進項稅額是否可扣抵。
     */
    public VatInputResult calculateVatInputDeduction(long inputVatMinor, InputVatCategory category) {
        if (inputVatMinor < 0) {
            throw new BusinessException("進項稅額不可為負");
        }
        if (category == null) {
            throw new BusinessException("進項稅額類別不可為空");
        }
        if (category.isDeductible()) {
            return new VatInputResult(inputVatMinor, inputVatMinor, 0L, category,
                    category.getLegalReason(), FilingStatus.INTERNAL_ESTIMATE_ONLY);
        }
        return new VatInputResult(inputVatMinor, 0L, inputVatMinor, category,
                category.getLegalReason(), FilingStatus.INTERNAL_ESTIMATE_ONLY);
    }

    /**
     * 本期營業稅應納／留抵稅額試算（銷項稅額 - 可扣抵進項稅額 - 上期留抵稅額）。
     */
    public VatPeriodResult calculatePeriodVatPayable(long outputVatMinor, long deductibleInputVatMinor,
                                                     long priorCarryforwardCreditMinor) {
        if (outputVatMinor < 0 || deductibleInputVatMinor < 0 || priorCarryforwardCreditMinor < 0) {
            throw new BusinessException("營業稅申報試算金額不可為負");
        }
        long net = outputVatMinor - deductibleInputVatMinor - priorCarryforwardCreditMinor;
        long payable = Math.max(0L, net);
        long carryforward = net < 0 ? -net : 0L;
        return new VatPeriodResult(outputVatMinor, deductibleInputVatMinor, priorCarryforwardCreditMinor,
                payable, carryforward, FilingStatus.INTERNAL_ESTIMATE_ONLY);
    }

    /**
     * 營利事業所得稅課稅所得額調整與應納稅額試算（含帳載純益、帳外剔除不可減除費用、免稅所得及前十年虧損扣抵）。
     */
    public CitResult calculateCorporateIncomeTaxAdjusted(long bookNetIncomeMinor,
                                                         long nonDeductibleExpenseMinor,
                                                         long taxExemptIncomeMinor,
                                                         long priorLossCarryforwardMinor) {
        if (nonDeductibleExpenseMinor < 0 || taxExemptIncomeMinor < 0 || priorLossCarryforwardMinor < 0) {
            throw new BusinessException("稅務調整項與前十年虧損扣抵金額不可為負");
        }
        long adjusted = bookNetIncomeMinor + nonDeductibleExpenseMinor - taxExemptIncomeMinor;
        long taxable = adjusted > 0 ? Math.max(0L, adjusted - priorLossCarryforwardMinor) : 0L;
        return calculateCorporateIncomeTax(taxable);
    }

    /**
     * 營利事業所得稅試算（所得稅法第 5 條第 5 項）：
     * - 課稅所得額 <= 120,000 元：免徵
     * - 超過 120,000 元至 200,000 元以下：應納稅額不超過超過 120,000 元部分之半數
     * - 超過 200,000 元：適用全額 20% 稅率
     */
    public CitResult calculateCorporateIncomeTax(long annualTaxableIncomeMinor) {
        if (annualTaxableIncomeMinor <= CIT_EXEMPTION_MINOR) {
            return new CitResult(annualTaxableIncomeMinor, 0L,
                    "免稅（未逾新臺幣 120,000 元起徵額）", FilingStatus.INTERNAL_ESTIMATE_ONLY);
        }
        long fullTwentyPercent = BigDecimal.valueOf(annualTaxableIncomeMinor)
                .multiply(new BigDecimal("0.20"))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
        long halfExcessCap = (annualTaxableIncomeMinor - CIT_EXEMPTION_MINOR) / 2;
        if (halfExcessCap < fullTwentyPercent) {
            return new CitResult(annualTaxableIncomeMinor, halfExcessCap,
                    "適用半數課稅上限規定（應納稅額不得超過逾 120,000 元部分之 50%）",
                    FilingStatus.INTERNAL_ESTIMATE_ONLY);
        }
        return new CitResult(annualTaxableIncomeMinor, fullTwentyPercent,
                "適用標準稅率 20%", FilingStatus.INTERNAL_ESTIMATE_ONLY);
    }

    /**
     * 各類所得扣繳稅額試算（依各類所得扣繳率標準）：
     * - SALARY（薪資）: 居住者 5% / 非居住者 18%
     * - RENT / PROFESSIONAL / ROYALTY / COMMISSION / INTEREST: 居住者 10% / 非居住者 20%
     * - DIVIDEND（股利）: 居住者 0% / 非居住者 21%
     * 居住者每次應扣繳稅額不超過新臺幣 2,000 元免予扣繳；非居住者給付日起 10 日內須辦理扣繳申報。
     */
    public WithholdingResult calculateWithholding(long grossPayMinor, String incomeCategory, boolean isResident) {
        if (grossPayMinor < 0) {
            throw new BusinessException("給付總額不可為負");
        }
        String cat = incomeCategory == null ? "" : incomeCategory.trim().toUpperCase();
        BigDecimal rate = switch (cat) {
            case "SALARY" -> isResident ? new BigDecimal("0.05") : new BigDecimal("0.18");
            case "RENT", "PROFESSIONAL", "ROYALTY", "COMMISSION", "INTEREST" ->
                    isResident ? new BigDecimal("0.10") : new BigDecimal("0.20");
            case "DIVIDEND" -> isResident ? BigDecimal.ZERO : new BigDecimal("0.21");
            default -> throw new BusinessException("不支援的扣繳所得類別: " + incomeCategory);
        };
        long tax = BigDecimal.valueOf(grossPayMinor)
                .multiply(rate)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
        if (isResident && tax <= RESIDENT_WITHHOLDING_EXEMPT_MINOR) {
            tax = 0L;
        }
        int deadlineDays = isResident ? 0 : 10;
        return new WithholdingResult(grossPayMinor, tax, grossPayMinor - tax,
                !isResident, deadlineDays, FilingStatus.INTERNAL_ESTIMATE_ONLY);
    }
}
