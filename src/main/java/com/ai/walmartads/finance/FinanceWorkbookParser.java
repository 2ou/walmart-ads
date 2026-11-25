package com.ai.walmartads.finance;

import com.ai.walmartads.finance.FinanceModels.ParsedFinanceWorkbook;
import com.ai.walmartads.finance.FinanceModels.StoreMonthlyRow;
import com.ai.walmartads.finance.FinanceModels.StyleMonthlyRow;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Streaming OOXML parser for Walmart finance profit workbooks across all 3 template eras (2024–2026).
 *
 * <p>Uses {@link XSSFReader} + StAX to read only the store P&amp;L and style P&amp;L sheets (and columns AB+
 * of Era-1 {@code Oder YYYY.MM} sheets), completely skipping 200k-row raw transaction sheets so 30MB+
 * multi-account workbooks parse in milliseconds with tiny heap usage.</p>
 */
@Component
public class FinanceWorkbookParser {

    private static final String ERROR_PREFIX = "__ERR__:";
    private static final Pattern FILE_YEAR_MONTH = Pattern.compile("(202\\d)年\\s*0?(\\d{1,2})月");
    private static final Pattern MONTH_HEADER = Pattern.compile("^0?(\\d{1,2})月(.*)$");
    private static final Pattern ODER_SHEET = Pattern.compile("^Oder\\s+(202\\d)\\.(\\d{2})$");
    private static final Pattern CELL_COL = Pattern.compile("^([A-Z]+)");
    private static final Pattern STYLE_SKU = Pattern.compile("[A-Z0-9][A-Z0-9_-]{0,79}");

    record ImportAccount(String code, String companyName, List<String> skuPrefixes) {
        boolean allowsStyle(String sku) {
            return STYLE_SKU.matcher(sku).matches()
                    && (skuPrefixes.isEmpty() || skuPrefixes.stream().anyMatch(sku::startsWith));
        }
    }

    public record YearMonthKey(int year, int month) implements Comparable<YearMonthKey> {
        public String formatted() {
            return String.format("%04d-%02d", year, month);
        }

        @Override
        public int compareTo(YearMonthKey other) {
            int cmp = Integer.compare(this.year, other.year);
            return cmp != 0 ? cmp : Integer.compare(this.month, other.month);
        }
    }

    public static Optional<YearMonthKey> extractFileYearMonth(String fileName) {
        if (fileName == null) return Optional.empty();
        Matcher matcher = FILE_YEAR_MONTH.matcher(fileName);
        if (!matcher.find()) return Optional.empty();
        int year = Integer.parseInt(matcher.group(1));
        int month = Integer.parseInt(matcher.group(2));
        if (month < 1 || month > 12) return Optional.empty();
        return Optional.of(new YearMonthKey(year, month));
    }

    public static boolean isProfitCandidate(String fileName) {
        if (fileName == null || fileName.startsWith("~$")) return false;
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".xlsx")) return false;
        if (!fileName.contains("利润表")) return false;
        return extractFileYearMonth(fileName).isPresent();
    }

    Optional<ParsedFinanceWorkbook> parseWorkbook(Path path, ImportAccount account) throws Exception {
        String fileName = path.getFileName().toString();
        if (!isProfitCandidate(fileName)) {
            return Optional.empty();
        }
        YearMonthKey fileYm = extractFileYearMonth(fileName).orElseThrow();
        String sha256 = sha256(path);

        try (OPCPackage pkg = OPCPackage.open(path.toFile(), PackageAccess.READ)) {
            ReadOnlySharedStringsTable sharedStrings = new ReadOnlySharedStringsTable(pkg);
            XSSFReader reader = new XSSFReader(pkg);

            // First pass: inspect sheet names and capture only needed sheets
            Map<String, List<Map<Integer, String>>> capturedSheets = new LinkedHashMap<>();
            List<String> allSheetNames = new ArrayList<>();
            XSSFReader.SheetIterator iter = (XSSFReader.SheetIterator) reader.getSheetsData();
            while (iter.hasNext()) {
                try (InputStream sheetStream = iter.next()) {
                    String name = iter.getSheetName();
                    allSheetNames.add(name);
                    if (isStoreSheet(name)) {
                        capturedSheets.put(name, readSheetCells(sheetStream, sharedStrings, 0, 35, 300));
                    } else if (isStyleSheet(name)) {
                        capturedSheets.put(name, readSheetCells(sheetStream, sharedStrings, 0, 45, 600));
                    } else if (ODER_SHEET.matcher(name.trim()).matches()) {
                        // Era 1 embedded style summary sits at column AB (index 27) onwards, rows 1..150
                        capturedSheets.put(name, readSheetCells(sheetStream, sharedStrings, 26, 55, 150));
                    }
                }
            }

            boolean hasSeparateStyleSheet = allSheetNames.stream().anyMatch(FinanceWorkbookParser::isStyleSheet);
            String era = resolveEra(hasSeparateStyleSheet, fileYm);

            List<StoreMonthlyRow> storeRows = new ArrayList<>();
            Map<String, BigDecimal> exchangeRatesByMonth = new HashMap<>();
            Map<String, BigDecimal> storeRevenueByMonth = new HashMap<>();

            for (Map.Entry<String, List<Map<Integer, String>>> entry : capturedSheets.entrySet()) {
                if (isStoreSheet(entry.getKey())) {
                    List<StoreMonthlyRow> parsed = parseStoreSheet(
                            entry.getKey(), entry.getValue(), fileYm.year(), fileName, account);
                    storeRows.addAll(parsed);
                    for (StoreMonthlyRow row : parsed) {
                        exchangeRatesByMonth.put(row.reportMonth(), row.exchangeRate());
                        storeRevenueByMonth.put(row.reportMonth(), row.salesRevenueUsd());
                    }
                }
            }

            if (storeRows.isEmpty()) {
                return Optional.empty();
            }

            Map<String, List<StyleMonthlyRow>> styleMonths = new LinkedHashMap<>();
            if (hasSeparateStyleSheet) {
                for (Map.Entry<String, List<Map<Integer, String>>> entry : capturedSheets.entrySet()) {
                    if (isStyleSheet(entry.getKey())) {
                        List<StyleMonthlyRow> styles = parseDedicatedStyleSheet(
                                entry.getValue(), fileYm, fileName, account,
                                exchangeRatesByMonth.getOrDefault(fileYm.formatted(), BigDecimal.ZERO),
                                storeRevenueByMonth.get(fileYm.formatted()));
                        if (!styles.isEmpty()) {
                            styleMonths.put(fileYm.formatted(), styles);
                        }
                    }
                }
            } else {
                for (Map.Entry<String, List<Map<Integer, String>>> entry : capturedSheets.entrySet()) {
                    Matcher matcher = ODER_SHEET.matcher(entry.getKey().trim());
                    if (matcher.matches()) {
                        YearMonthKey sheetYm = new YearMonthKey(
                                Integer.parseInt(matcher.group(1)),
                                Integer.parseInt(matcher.group(2)));
                        List<StyleMonthlyRow> styles = parseEmbeddedOderStyleSheet(
                                entry.getValue(), sheetYm, fileName, account,
                                exchangeRatesByMonth.getOrDefault(sheetYm.formatted(), BigDecimal.ZERO),
                                storeRevenueByMonth.get(sheetYm.formatted()));
                        if (!styles.isEmpty()) {
                            styleMonths.put(sheetYm.formatted(), styles);
                        }
                    }
                }
            }

            return Optional.of(new ParsedFinanceWorkbook(
                    fileName,
                    sha256,
                    era,
                    fileYm.formatted(),
                    account.code(),
                    storeRows,
                    styleMonths
            ));
        }
    }

    private List<StoreMonthlyRow> parseStoreSheet(
            String sheetName,
            List<Map<Integer, String>> rows,
            int fileYear,
            String fileName,
            ImportAccount account
    ) {
        if (rows.size() < 3) return List.of();
        boolean singleAccountLayout = "Walmart-利润表".equals(sheetName.trim());
        String title = cleanText(rows.get(0).get(0));
        if (singleAccountLayout && !Pattern.compile("(?<![A-Z0-9_])" + Pattern.quote(account.code()) + "(?![A-Z0-9_])",
                Pattern.CASE_INSENSITIVE).matcher(title).find()) {
            return List.of();
        }

        Map<Integer, String> headerRow = rows.get(1);
        record MonthCol(int month, int colIndex, String settlementPeriod) {}
        List<MonthCol> monthCols = new ArrayList<>();
        for (Map.Entry<Integer, String> cell : headerRow.entrySet()) {
            String text = cleanText(cell.getValue());
            if (text.isEmpty() || text.contains("占比")) continue;
            Matcher m = MONTH_HEADER.matcher(text);
            if (m.matches()) {
                int month = Integer.parseInt(m.group(1));
                if (month >= 1 && month <= 12) {
                    String period = cleanPeriod(m.group(2));
                    monthCols.add(new MonthCol(month, cell.getKey(), period));
                }
            }
        }
        if (monthCols.isEmpty()) return List.of();

        String currentAccount = singleAccountLayout ? account.code() : "";
        Map<String, Map<Integer, String>> accountItemRows = new LinkedHashMap<>();
        for (int r = 2; r < rows.size(); r++) {
            Map<Integer, String> row = rows.get(r);
            if (row == null || row.isEmpty()) continue;
            String item;
            if (singleAccountLayout) {
                item = cleanText(row.get(0));
            } else {
                String accCell = cleanText(row.get(0));
                if ("账号".equals(accCell)) continue;
                if (!accCell.isEmpty()) {
                    currentAccount = accCell.toUpperCase(Locale.ROOT);
                }
                item = cleanText(row.get(1));
            }
            if (!account.code().equalsIgnoreCase(currentAccount) || item.isEmpty()) continue;
            accountItemRows.put(item, row);
        }
        if (accountItemRows.isEmpty()) return List.of();

        List<StoreMonthlyRow> result = new ArrayList<>();
        for (MonthCol mc : monthCols) {
            BigDecimal revUsd = decimalAt(accountItemRows, "销售收入", mc.colIndex());
            BigDecimal grossProfitCny = decimalAt(accountItemRows, "毛利", mc.colIndex());
            if (isZero(revUsd) && isZero(grossProfitCny)) {
                continue;
            }
            BigDecimal refundUsd = decimalAt(accountItemRows, "销售订单退款", mc.colIndex());
            BigDecimal commissionUsd = decimalAt(accountItemRows, "平台佣金", mc.colIndex());
            BigDecimal wfsFeeUsd = decimalAt(accountItemRows, "WFS配送", mc.colIndex());
            BigDecimal adSpendUsd = decimalAt(accountItemRows, "广告费", mc.colIndex());
            BigDecimal storageFeeUsd = decimalAt(accountItemRows, "仓储费", mc.colIndex());
            BigDecimal invReimbUsd = decimalAt(accountItemRows, "库存赔偿费", mc.colIndex());
            BigDecimal invDispUsd = decimalAt(accountItemRows, "库存处理费", mc.colIndex());
            BigDecimal promoUsd = decimalAt(accountItemRows, "促销折扣", mc.colIndex());
            BigDecimal otherUsd = decimalAt(accountItemRows, "平台其他费用", mc.colIndex());
            BigDecimal netUsd = decimalAt(accountItemRows, "当月结算款", mc.colIndex());
            BigDecimal fx = decimalAt(accountItemRows, "汇率", mc.colIndex());
            BigDecimal netCny = decimalAt(accountItemRows, "结算金额（CNY）", mc.colIndex());
            if (isZero(netCny) && !isZero(netUsd) && !isZero(fx)) {
                netCny = netUsd.multiply(fx).setScale(4, RoundingMode.HALF_UP);
            }

            BigDecimal rawPur = decimalAt(accountItemRows, "采购成本", mc.colIndex());
            BigDecimal rawFl = decimalAt(accountItemRows, "头程运费", mc.colIndex());
            BigDecimal rawSelfShip = decimalAt(accountItemRows, "自配送运费", mc.colIndex());
            BigDecimal rawOffAd = decimalAt(accountItemRows, "站外广告费", mc.colIndex());
            BigDecimal rawOffFee = decimalAt(accountItemRows, "站外费", mc.colIndex());

            // Normalize CNY cost sign using algebraic reconciliation against grossProfitCny:
            // normalized convention: positive = cost expense, negative = cost reversal / rebate.
            BigDecimal rawSum = rawPur.add(rawFl).add(rawSelfShip).add(rawOffAd).add(rawOffFee);
            BigDecimal errIfPositiveExpense = netCny.subtract(rawSum).subtract(grossProfitCny).abs();
            BigDecimal errIfNegativeExpense = netCny.add(rawSum).subtract(grossProfitCny).abs();
            boolean flip = errIfNegativeExpense.compareTo(errIfPositiveExpense) < 0;

            BigDecimal purCny = flip ? rawPur.negate() : rawPur;
            BigDecimal flCny = flip ? rawFl.add(rawSelfShip).negate() : rawFl.add(rawSelfShip);
            BigDecimal offAdCny = flip ? rawOffAd.negate() : rawOffAd;
            BigDecimal offFeeCny = flip ? rawOffFee.negate() : rawOffFee;

            BigDecimal settlementRatio = !isZero(revUsd)
                    ? netUsd.divide(revUsd, 6, RoundingMode.HALF_UP)
                    : decimalAt(accountItemRows, "当月结算比例", mc.colIndex());
            BigDecimal revCny = revUsd.multiply(fx);
            BigDecimal profitMargin = !isZero(revCny)
                    ? grossProfitCny.divide(revCny, 6, RoundingMode.HALF_UP)
                    : decimalAt(accountItemRows, "利润率", mc.colIndex());

            int salesQty = Math.abs(intAt(accountItemRows, "总销量件数", mc.colIndex()));
            int refundQty = Math.abs(intAt(accountItemRows, "退款订单数", mc.colIndex()));
            int returnQty = Math.abs(intAt(accountItemRows, "退货件数", mc.colIndex()));
            Integer returnSellableQty = nullableAbsIntAt(accountItemRows, "退货件数（可售）", mc.colIndex());

            String ym = String.format("%04d-%02d", fileYear, mc.month());
            result.add(new StoreMonthlyRow(
                    account.code(),
                    account.companyName(),
                    fileYear,
                    ym,
                    mc.settlementPeriod().isEmpty() ? null : mc.settlementPeriod(),
                    scale(fx, 6),
                    scale(revUsd, 4),
                    scale(refundUsd, 4),
                    scale(commissionUsd, 4),
                    scale(wfsFeeUsd, 4),
                    scale(adSpendUsd, 4),
                    scale(storageFeeUsd, 4),
                    scale(invReimbUsd, 4),
                    scale(invDispUsd, 4),
                    scale(promoUsd, 4),
                    scale(otherUsd, 4),
                    scale(netUsd, 4),
                    scale(settlementRatio, 6),
                    scale(netCny, 4),
                    scale(purCny, 4),
                    scale(flCny, 4),
                    scale(offAdCny, 4),
                    scale(offFeeCny, 4),
                    scale(grossProfitCny, 4),
                    scale(profitMargin, 6),
                    salesQty,
                    refundQty,
                    returnQty,
                    returnSellableQty,
                    fileName
            ));
        }
        return result;
    }

    private List<StyleMonthlyRow> parseDedicatedStyleSheet(
            List<Map<Integer, String>> rows,
            YearMonthKey ym,
            String fileName,
            ImportAccount account,
            BigDecimal fallbackFx,
            BigDecimal expectedStoreRevUsd
    ) {
        if (rows.size() < 2) return List.of();
        Map<String, Integer> headerCols = indexHeaders(rows.get(0));
        Integer siteCol = headerCols.get("站点");
        Integer skuCol = headerCols.get("SKU");
        if (siteCol == null || skuCol == null) return List.of();

        List<StyleMonthlyRow> styles = new ArrayList<>();
        boolean hasBrokenFormula = false;
        BigDecimal totalStyleRev = BigDecimal.ZERO;

        for (int r = 1; r < rows.size(); r++) {
            Map<Integer, String> row = rows.get(r);
            if (row == null || row.isEmpty()) continue;
            String site = cleanText(row.get(siteCol));
            if (!account.code().equalsIgnoreCase(site)) continue;
            String sku = cleanText(row.get(skuCol)).toUpperCase(Locale.ROOT);
            if (!account.allowsStyle(sku)) continue;

            if (hasFormulaError(row, headerCols, "销售净额", "销售净额(CNY)", "利润", "销售额", "销售收入")) {
                hasBrokenFormula = true;
                continue;
            }

            StyleMonthlyRow item = buildStyleRow(row, headerCols, ym, fileName, account, fallbackFx, true);
            totalStyleRev = totalStyleRev.add(item.salesRevenueUsd());
            styles.add(item);
        }

        if (hasBrokenFormula || isBrokenRelativeToStore(totalStyleRev, expectedStoreRevUsd)) {
            return List.of();
        }
        return styles;
    }

    private List<StyleMonthlyRow> parseEmbeddedOderStyleSheet(
            List<Map<Integer, String>> rows,
            YearMonthKey ym,
            String fileName,
            ImportAccount account,
            BigDecimal storeFx,
            BigDecimal expectedStoreRevUsd
    ) {
        if (rows.size() < 2) return List.of();
        Map<Integer, String> firstRow = rows.get(0);
        Integer skuCol = null;
        for (Map.Entry<Integer, String> entry : firstRow.entrySet()) {
            if (entry.getKey() >= 26 && "SKU".equalsIgnoreCase(cleanText(entry.getValue()))) {
                skuCol = entry.getKey();
                break;
            }
        }
        if (skuCol == null) return List.of();

        Map<String, Integer> headerCols = new HashMap<>();
        for (Map.Entry<Integer, String> entry : firstRow.entrySet()) {
            if (entry.getKey() >= skuCol) {
                String h = cleanText(entry.getValue());
                if (!h.isEmpty()) {
                    headerCols.putIfAbsent(h, entry.getKey());
                }
            }
        }

        Integer siteCol = headerCols.get("站点");
        if (siteCol == null && account.skuPrefixes().isEmpty()) return List.of();

        List<StyleMonthlyRow> styles = new ArrayList<>();
        boolean hasBrokenFormula = false;
        BigDecimal totalStyleRev = BigDecimal.ZERO;

        for (int r = 1; r < rows.size(); r++) {
            Map<Integer, String> row = rows.get(r);
            if (row == null || row.isEmpty()) continue;
            String skuRaw = cleanText(row.get(skuCol));
            if (skuRaw.isEmpty()) continue;
            if (skuRaw.startsWith("合计") || skuRaw.startsWith("核对")) break;
            if (siteCol != null && !account.code().equalsIgnoreCase(cleanText(row.get(siteCol)))) continue;
            String sku = skuRaw.toUpperCase(Locale.ROOT);
            if (!account.allowsStyle(sku)) continue;

            if (hasFormulaError(row, headerCols, "销售净额", "销售净额(CNY)", "利润", "平台佣金", "广告费")) {
                hasBrokenFormula = true;
                continue;
            }

            StyleMonthlyRow item = buildStyleRow(row, headerCols, ym, fileName, account, storeFx, false);
            totalStyleRev = totalStyleRev.add(item.salesRevenueUsd());
            styles.add(item);
        }

        if (hasBrokenFormula || isBrokenRelativeToStore(totalStyleRev, expectedStoreRevUsd)) {
            return List.of();
        }
        return styles;
    }

    private StyleMonthlyRow buildStyleRow(
            Map<Integer, String> row,
            Map<String, Integer> headerCols,
            YearMonthKey ym,
            String fileName,
            ImportAccount account,
            BigDecimal fallbackFx,
            boolean dedicatedSheet
    ) {
        String sku = cleanText(cellByHeaders(row, headerCols, "SKU")).toUpperCase(Locale.ROOT);
        String company = nullableMeta(cellByHeaders(row, headerCols, "公司"));
        if (company == null) company = account.companyName();
        String country = nullableMeta(cellByHeaders(row, headerCols, "国家"));
        if (country == null) country = "US";
        String listingStatus = nullableMeta(cellByHeaders(row, headerCols, "Listing状态"));
        String season = nullableMeta(cellByHeaders(row, headerCols, "季节"));
        String category = nullableMeta(cellByHeaders(row, headerCols, "类目"));
        String owner = nullableMeta(cellByHeaders(row, headerCols, "负责人"));

        BigDecimal fx = decimalByHeaders(row, headerCols, "汇率");
        if (isZero(fx)) fx = fallbackFx;

        int salesQty = Math.abs(intByHeaders(row, headerCols, "销量", "销售数量"));
        BigDecimal salesRevUsd = decimalByHeaders(row, headerCols, "销售额", "销售收入");
        BigDecimal asp = decimalByHeaders(row, headerCols, "客单价");
        if (isZero(asp) && salesQty > 0) {
            asp = salesRevUsd.divide(BigDecimal.valueOf(salesQty), 4, RoundingMode.HALF_UP);
        }

        int refundQty = Math.abs(intByHeaders(row, headerCols, "退款量", "退款数量"));
        BigDecimal refundUsd = decimalByHeaders(row, headerCols, "退款");
        BigDecimal refundRate = salesQty > 0
                ? BigDecimal.valueOf(refundQty).divide(BigDecimal.valueOf(salesQty), 6, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        int returnQty = Math.abs(intByHeaders(row, headerCols, "退货数量"));

        BigDecimal wfsFeeUsd = decimalByHeaders(row, headerCols, "WFS发货费", "WFS费用");
        BigDecimal commissionUsd = decimalByHeaders(row, headerCols, "平台费（佣金）", "平台佣金");
        BigDecimal promoDiscountUsd = decimalByHeaders(row, headerCols, "促销折扣");
        BigDecimal adSpendUsd = decimalByHeaders(row, headerCols, "广告费");
        BigDecimal storageFeeUsd = decimalByHeaders(row, headerCols, "仓储费");
        BigDecimal invReimbUsd = decimalByHeaders(row, headerCols, "库存赔偿费");
        BigDecimal invDispUsd = decimalByHeaders(row, headerCols, "库存处理费");
        BigDecimal otherFeeUsd = decimalByHeaders(row, headerCols, "其他平台费用");
        BigDecimal netUsd = decimalByHeaders(row, headerCols, "销售净额");
        BigDecimal netCny = decimalByHeaders(row, headerCols, "销售净额(CNY)");

        // Keep exact sign for product & first-leg cost (negative means return cost reversal into sellable stock!)
        BigDecimal purchaseCostCny = decimalByHeaders(row, headerCols, "产品成本");
        BigDecimal firstLegCostCny = decimalByHeaders(row, headerCols, "头程成本", "运费");
        BigDecimal rawOffsiteCny = decimalByHeaders(row, headerCols, "站外费");
        BigDecimal profitCny = decimalByHeaders(row, headerCols, "利润");

        // Normalize offsiteFeeCny sign per row so profitCny == netCny - purchaseCostCny - firstLegCostCny - offsiteFeeCny
        BigDecimal offsiteFeeCny = rawOffsiteCny;
        if (!isZero(rawOffsiteCny)) {
            BigDecimal base = netCny.subtract(purchaseCostCny).subtract(firstLegCostCny);
            BigDecimal errIfPositiveExpense = base.subtract(rawOffsiteCny).subtract(profitCny).abs();
            BigDecimal errIfNegativeExpense = base.add(rawOffsiteCny).subtract(profitCny).abs();
            if (errIfNegativeExpense.compareTo(errIfPositiveExpense) < 0) {
                offsiteFeeCny = rawOffsiteCny.negate();
            } else if (errIfNegativeExpense.compareTo(errIfPositiveExpense) == 0 && dedicatedSheet
                    && (ym.year() > 2025 || (ym.year() == 2025 && ym.month() >= 11))) {
                offsiteFeeCny = rawOffsiteCny.negate();
            }
        }

        BigDecimal revCny = salesRevUsd.multiply(fx);
        BigDecimal profitMargin = !isZero(revCny)
                ? profitCny.divide(revCny, 6, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        return new StyleMonthlyRow(
                account.code(),
                company,
                country,
                ym.year(),
                ym.formatted(),
                sku,
                listingStatus,
                season,
                category,
                owner,
                scale(fx, 6),
                scale(asp, 4),
                salesQty,
                scale(salesRevUsd, 4),
                refundQty,
                scale(refundUsd, 4),
                scale(refundRate, 6),
                returnQty,
                scale(wfsFeeUsd, 4),
                scale(commissionUsd, 4),
                scale(promoDiscountUsd, 4),
                scale(adSpendUsd, 4),
                scale(storageFeeUsd, 4),
                scale(invReimbUsd, 4),
                scale(invDispUsd, 4),
                scale(otherFeeUsd, 4),
                scale(netUsd, 4),
                scale(netCny, 4),
                scale(purchaseCostCny, 4),
                scale(firstLegCostCny, 4),
                scale(offsiteFeeCny, 4),
                scale(profitCny, 4),
                scale(profitMargin, 6),
                fileName
        );
    }

    private List<Map<Integer, String>> readSheetCells(
            InputStream sheetStream,
            ReadOnlySharedStringsTable sharedStrings,
            int minCol,
            int maxCol,
            int maxRows
    ) throws Exception {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        XMLStreamReader xml = factory.createXMLStreamReader(sheetStream);

        List<Map<Integer, String>> result = new ArrayList<>();
        Map<Integer, String> currentRow = null;
        int currentRowNum = 0;
        int currentCol = -1;
        String currentType = null;
        boolean inValue = false;
        boolean inInlineStr = false;
        StringBuilder textBuf = new StringBuilder();

        try {
            while (xml.hasNext()) {
                int event = xml.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String tag = xml.getLocalName();
                    if ("row".equals(tag)) {
                        String rAttr = xml.getAttributeValue(null, "r");
                        currentRowNum = rAttr != null ? Integer.parseInt(rAttr) : currentRowNum + 1;
                        if (currentRowNum > maxRows) {
                            break;
                        }
                        while (result.size() < currentRowNum - 1) {
                            result.add(new HashMap<>());
                        }
                        currentRow = new HashMap<>();
                    } else if ("c".equals(tag) && currentRow != null) {
                        String ref = xml.getAttributeValue(null, "r");
                        currentCol = ref != null ? columnIndex(ref) : currentCol + 1;
                        currentType = xml.getAttributeValue(null, "t");
                    } else if ("v".equals(tag) && currentRow != null && currentCol >= minCol && currentCol <= maxCol) {
                        inValue = true;
                        textBuf.setLength(0);
                    } else if ("is".equals(tag) && currentRow != null && currentCol >= minCol && currentCol <= maxCol) {
                        inInlineStr = true;
                        textBuf.setLength(0);
                    }
                } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                    if (inValue || inInlineStr) {
                        textBuf.append(xml.getText());
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String tag = xml.getLocalName();
                    if ("v".equals(tag) && inValue) {
                        inValue = false;
                        String raw = textBuf.toString();
                        String resolved = resolveCellValue(raw, currentType, sharedStrings);
                        if (resolved != null) {
                            currentRow.put(currentCol, resolved);
                        }
                    } else if ("is".equals(tag) && inInlineStr) {
                        inInlineStr = false;
                        currentRow.put(currentCol, textBuf.toString());
                    } else if ("row".equals(tag) && currentRow != null) {
                        result.add(currentRow);
                        currentRow = null;
                    }
                }
            }
        } finally {
            xml.close();
        }
        return result;
    }

    private static String resolveCellValue(String raw, String cellType, ReadOnlySharedStringsTable sharedStrings) {
        if (raw == null) return null;
        if ("s".equals(cellType)) {
            int idx = Integer.parseInt(raw.trim());
            return sharedStrings.getItemAt(idx).getString();
        }
        if ("e".equals(cellType)) {
            return ERROR_PREFIX + raw.trim();
        }
        if ("b".equals(cellType)) {
            return "1".equals(raw.trim()) ? "TRUE" : "FALSE";
        }
        return raw;
    }

    private static int columnIndex(String cellRef) {
        Matcher m = CELL_COL.matcher(cellRef);
        if (!m.find()) return -1;
        String letters = m.group(1);
        int col = 0;
        for (int i = 0; i < letters.length(); i++) {
            col = col * 26 + (letters.charAt(i) - 'A' + 1);
        }
        return col - 1;
    }

    private static boolean isStoreSheet(String name) {
        return name != null && name.contains("利润表") && !name.contains("单款");
    }

    private static boolean isStyleSheet(String name) {
        return name != null && name.contains("单款");
    }

    private static String resolveEra(boolean hasSeparateStyleSheet, YearMonthKey ym) {
        if (!hasSeparateStyleSheet) return "ERA1_TEMPLATE";
        return ym.year() >= 2026 ? "ERA3_2026" : "ERA2_2025";
    }

    private static boolean isBrokenRelativeToStore(BigDecimal totalStyleRev, BigDecimal expectedStoreRevUsd) {
        return expectedStoreRevUsd != null
                && expectedStoreRevUsd.compareTo(BigDecimal.ONE) > 0
                && totalStyleRev.abs().compareTo(new BigDecimal("0.01")) < 0;
    }

    private static boolean hasFormulaError(
            Map<Integer, String> row, Map<String, Integer> headerCols, String... names) {
        for (String name : names) {
            Integer idx = headerCols.get(name);
            if (idx == null) continue;
            String val = row.get(idx);
            if (val != null && (val.startsWith(ERROR_PREFIX) || val.startsWith("#DIV/0!") || val.startsWith("#REF!"))) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Integer> indexHeaders(Map<Integer, String> headerRow) {
        Map<String, Integer> map = new HashMap<>();
        for (Map.Entry<Integer, String> e : headerRow.entrySet()) {
            String h = cleanText(e.getValue());
            if (!h.isEmpty()) {
                map.putIfAbsent(h, e.getKey());
            }
        }
        return map;
    }

    private static String cellByHeaders(Map<Integer, String> row, Map<String, Integer> headers, String... candidates) {
        for (String name : candidates) {
            Integer idx = headers.get(name);
            if (idx != null) {
                return row.get(idx);
            }
        }
        return null;
    }

    private static BigDecimal decimalByHeaders(Map<Integer, String> row, Map<String, Integer> headers, String... candidates) {
        return parseDecimal(cellByHeaders(row, headers, candidates));
    }

    private static int intByHeaders(Map<Integer, String> row, Map<String, Integer> headers, String... candidates) {
        return parseDecimal(cellByHeaders(row, headers, candidates)).setScale(0, RoundingMode.HALF_UP).intValue();
    }

    private static BigDecimal decimalAt(Map<String, Map<Integer, String>> itemRows, String item, int colIndex) {
        Map<Integer, String> row = itemRows.get(item);
        return row == null ? BigDecimal.ZERO : parseDecimal(row.get(colIndex));
    }

    private static int intAt(Map<String, Map<Integer, String>> itemRows, String item, int colIndex) {
        return decimalAt(itemRows, item, colIndex).setScale(0, RoundingMode.HALF_UP).intValue();
    }

    private static Integer nullableAbsIntAt(Map<String, Map<Integer, String>> itemRows, String item, int colIndex) {
        Map<Integer, String> row = itemRows.get(item);
        if (row == null) return null;
        String raw = row.get(colIndex);
        if (raw == null || raw.isBlank() || raw.startsWith(ERROR_PREFIX)) return null;
        return Math.abs(parseDecimal(raw).setScale(0, RoundingMode.HALF_UP).intValue());
    }

    private static BigDecimal parseDecimal(String raw) {
        if (raw == null) return BigDecimal.ZERO;
        String s = raw.trim();
        if (s.isEmpty() || s.startsWith(ERROR_PREFIX) || s.startsWith("#") || "-".equals(s)) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(s.replace(",", ""));
        } catch (NumberFormatException ignored) {
            return BigDecimal.ZERO;
        }
    }

    private static String cleanText(String raw) {
        if (raw == null || raw.startsWith(ERROR_PREFIX)) return "";
        String s = raw.trim();
        if (s.startsWith("#")) return "";
        return s;
    }

    private static String nullableMeta(String raw) {
        String s = cleanText(raw);
        if (s.isEmpty() || "0".equals(s) || "0.0".equals(s)) return null;
        return s;
    }

    private static String cleanPeriod(String raw) {
        if (raw == null) return "";
        return raw.replace("（", "").replace("）", "").replace("(", "").replace(")", "").trim();
    }

    private static boolean isZero(BigDecimal v) {
        return v == null || v.abs().compareTo(new BigDecimal("0.000001")) < 0;
    }

    private static BigDecimal scale(BigDecimal v, int digits) {
        return (v == null ? BigDecimal.ZERO : v).setScale(digits, RoundingMode.HALF_UP);
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(path)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    md.update(buf, 0, n);
                }
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("无法计算文件摘要", e);
        }
    }
}
