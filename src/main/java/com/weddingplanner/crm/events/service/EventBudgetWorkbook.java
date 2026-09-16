package com.weddingplanner.crm.events.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import com.weddingplanner.crm.events.domain.EventBudget;
import com.weddingplanner.crm.events.domain.EventProject;

/**
 * Estimates as the spreadsheet the client already works in.
 *
 * <p>Two shapes, because two questions get asked. <b>One estimate</b> is the working sheet: every
 * article with its comments, its supplier, what is committed against it and what is paid — the
 * detail behind the category totals on screen. <b>Scenarios</b> is the sheet the couple is sent:
 * category, position, comments, then a money column per venue and a total at the foot, which is the
 * layout the venue quotes arrive in.
 *
 * <p>Formatting is part of the deliverable — column widths, wrapped comments, a frozen header and a
 * euro number format — because a workbook that has to be reformatted before it can be read is not
 * much better than the PDF it replaced.
 */
@Service
public class EventBudgetWorkbook {
    private final EventBudgetReport report;

    public EventBudgetWorkbook(EventBudgetReport report) { this.report = report; }

    public static final String CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy");

    /** The styles one workbook uses. POI caches styles per workbook, so they are made once. */
    private record Styles(CellStyle title, CellStyle subtitle, CellStyle header, CellStyle band,
            CellStyle text, CellStyle wrapped, CellStyle muted, CellStyle money, CellStyle moneyMuted,
            CellStyle quantity, CellStyle bandMoney, CellStyle totalText, CellStyle totalMoney) {}

    private static Font font(Workbook book, boolean bold, int points, String colour) {
        var font = book.createFont();
        font.setBold(bold);
        font.setFontHeightInPoints((short) points);
        if (colour != null) font.setColor(IndexedColors.valueOf(colour).getIndex());
        return font;
    }

    private static Styles styles(Workbook book) {
        var euros = book.createDataFormat().getFormat("#,##0.00\\ \"€\";[Red]-#,##0.00\\ \"€\"");
        var quantities = book.createDataFormat().getFormat("#,##0.###");

        var title = book.createCellStyle(); title.setFont(font(book, true, 16, null));
        var subtitle = book.createCellStyle(); subtitle.setFont(font(book, false, 10, "GREY_50_PERCENT"));

        var header = book.createCellStyle();
        header.setFont(font(book, true, 10, "WHITE"));
        header.setFillForegroundColor(IndexedColors.GREY_50_PERCENT.getIndex());
        header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        header.setVerticalAlignment(VerticalAlignment.CENTER);
        header.setWrapText(true);

        var band = book.createCellStyle();
        band.setFont(font(book, true, 11, null));
        band.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        band.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        var text = book.createCellStyle(); text.setVerticalAlignment(VerticalAlignment.TOP);
        var wrapped = book.createCellStyle();
        wrapped.setVerticalAlignment(VerticalAlignment.TOP); wrapped.setWrapText(true);
        var muted = book.createCellStyle();
        muted.setVerticalAlignment(VerticalAlignment.TOP); muted.setFont(font(book, false, 10, "GREY_50_PERCENT"));

        var money = book.createCellStyle();
        money.setDataFormat(euros); money.setVerticalAlignment(VerticalAlignment.TOP);
        var moneyMuted = book.createCellStyle();
        moneyMuted.setDataFormat(euros); moneyMuted.setVerticalAlignment(VerticalAlignment.TOP);
        moneyMuted.setFont(font(book, false, 10, "GREY_50_PERCENT"));
        var quantity = book.createCellStyle();
        quantity.setDataFormat(quantities); quantity.setVerticalAlignment(VerticalAlignment.TOP);

        var bandMoney = book.createCellStyle();
        bandMoney.cloneStyleFrom(band); bandMoney.setDataFormat(euros);

        var totalText = book.createCellStyle();
        totalText.setFont(font(book, true, 12, null));
        totalText.setBorderTop(BorderStyle.THIN);
        var totalMoney = book.createCellStyle();
        totalMoney.cloneStyleFrom(totalText); totalMoney.setDataFormat(euros);

        return new Styles(title, subtitle, header, band, text, wrapped, muted, money, moneyMuted,
                quantity, bandMoney, totalText, totalMoney);
    }

    private static Cell cell(Row row, int column, String value, CellStyle style) {
        var cell = row.createCell(column);
        if (value != null) cell.setCellValue(value);
        cell.setCellStyle(style);
        return cell;
    }

    /** A blank money cell means "nothing here"; a zero would read as a price of nothing. */
    private static Cell money(Row row, int column, BigDecimal value, CellStyle style) {
        var cell = row.createCell(column);
        if (value != null) cell.setCellValue(value.doubleValue());
        cell.setCellStyle(style);
        return cell;
    }

    private static void widths(Sheet sheet, int... characters) {
        for (int i = 0; i < characters.length; i++) sheet.setColumnWidth(i, characters[i] * 256);
    }

    /** Event, client, dates, venue — the same facts that head the event page. */
    private String context(EventProject event) {
        var parts = new java.util.ArrayList<String>();
        var client = report.contact(event.getClient());
        if (client != null) parts.add(client);
        if (event.getStartDate() != null) parts.add(event.getEndDate() == null || event.getEndDate().equals(event.getStartDate())
                ? DAY.format(event.getStartDate())
                : DAY.format(event.getStartDate()) + " – " + DAY.format(event.getEndDate()));
        if (event.getLocation() != null && !event.getLocation().isBlank()) parts.add(event.getLocation());
        if (event.getGuests() != null) parts.add(event.getGuests() + " guests");
        parts.add(event.getCode());
        return String.join("  ·  ", parts);
    }

    private static void heading(Sheet sheet, Styles styles, String title, String context, int span) {
        var first = sheet.createRow(0);
        cell(first, 0, title, styles.title()).getRow().setHeightInPoints(22);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, span));
        var second = sheet.createRow(1);
        cell(second, 0, context, styles.subtitle());
        sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, span));
    }

    private static void header(Sheet sheet, Styles styles, int index, String... labels) {
        var row = sheet.createRow(index);
        row.setHeightInPoints(28);
        for (int i = 0; i < labels.length; i++) cell(row, i, labels[i], styles.header());
        sheet.createFreezePane(0, index + 1);
        sheet.setAutoFilter(new CellRangeAddress(index, index, 0, labels.length - 1));
    }

    /** One estimate in full: every article, its comments, its supplier, and what it has cost so far. */
    public byte[] estimate(EventProject event, EventBudget budget) throws IOException {
        var breakdown = report.breakdown(event, budget);
        try (var book = new XSSFWorkbook(); var bytes = new ByteArrayOutputStream()) {
            var styles = styles(book);
            var sheet = book.createSheet(sheetName(EventBudgetReport.label(budget)));
            widths(sheet, 24, 34, 58, 24, 20, 16, 16, 8, 15, 15, 15, 15);

            heading(sheet, styles, (event.getDescription() == null ? event.getCode() : event.getDescription())
                    + " — " + EventBudgetReport.label(budget), context(event), 11);
            var basis = sheet.createRow(2);
            cell(basis, 0, String.join("  ·  ", List.of(budget.getNumber() == null ? "Draft" : budget.getNumber(),
                    budget.getPriceBasis() == null ? "Price basis not stated" : budget.getPriceBasis())),
                    styles.subtitle());
            sheet.addMergedRegion(new CellRangeAddress(2, 2, 0, 11));

            header(sheet, styles, 4, "Category", "Position", "What it covers", "Contractor", "Event day",
                    "Charge type", "Status", "Qty", "Unit price", "Estimated", "Committed", "Paid");

            int index = 5;
            for (var category : breakdown.categories()) {
                var band = sheet.createRow(index++);
                cell(band, 0, category.category(), styles.band());
                for (int column = 1; column <= 8; column++) cell(band, column, null, styles.band());
                money(band, 9, category.estimated(), styles.bandMoney());
                money(band, 10, category.committed(), styles.bandMoney());
                money(band, 11, category.paid(), styles.bandMoney());

                for (var line : category.lines()) {
                    var row = sheet.createRow(index++);
                    // The category repeats on every line as well as heading the band, so the sheet's
                    // autofilter can narrow to one category without losing the label.
                    cell(row, 0, line.category(), styles.muted());
                    cell(row, 1, line.article(), styles.text());
                    cell(row, 2, line.details(), styles.wrapped());
                    cell(row, 3, line.contractor(), styles.text());
                    cell(row, 4, line.phaseLabel(), styles.text());
                    cell(row, 5, line.kindLabel(), styles.text());
                    cell(row, 6, line.budgeted() ? line.stateLabel() : "Unbudgeted",
                            line.budgeted() ? styles.text() : styles.muted());
                    var quantity = row.createCell(7);
                    if (line.quantity() != null) quantity.setCellValue(line.quantity().doubleValue());
                    quantity.setCellStyle(styles.quantity());
                    money(row, 8, line.unitPrice(), styles.money());
                    money(row, 9, line.amount(), styles.money());
                    money(row, 10, line.committed().signum() == 0 ? null : line.committed(), styles.money());
                    money(row, 11, line.paid().signum() == 0 ? null : line.paid(), styles.moneyMuted());

                    // The supplier invoices behind a committed figure, indented under their article, so
                    // "€40,000 committed" can be traced to the invoice that committed it.
                    for (var commitment : line.commitments()) {
                        var detail = sheet.createRow(index++);
                        cell(detail, 2, "↳ " + commitment.number() + " · " + commitment.counterparty(), styles.muted());
                        money(detail, 10, commitment.amount(), styles.moneyMuted());
                        money(detail, 11, commitment.paid().signum() == 0 ? null : commitment.paid(), styles.moneyMuted());
                    }
                }
            }

            var total = sheet.createRow(index + 1);
            cell(total, 0, "Total", styles.totalText());
            for (int column = 1; column <= 8; column++) cell(total, column, null, styles.totalText());
            money(total, 9, breakdown.estimated(), styles.totalMoney());
            money(total, 10, breakdown.committed(), styles.totalMoney());
            money(total, 11, breakdown.paid(), styles.totalMoney());

            int note = index + 3;
            int unpriced = breakdown.categories().stream().mapToInt(EventBudgetReport.Category::unpriced).sum();
            if (unpriced > 0) cell(sheet.createRow(note++), 0,
                    unpriced + " article(s) are still unpriced and are not in the total.", styles.subtitle());
            if (budget.getRefundableDeposit() != null && budget.getRefundableDeposit().signum() > 0) {
                var row = sheet.createRow(note++);
                cell(row, 0, "Refundable deposit, outside the budget", styles.subtitle());
                money(row, 9, budget.getRefundableDeposit(), styles.money());
            }
            if (budget.getSourceFile() != null && !budget.getSourceFile().isBlank())
                cell(sheet.createRow(note), 0, "Source: " + budget.getSourceFile(), styles.subtitle());

            summary(book, styles, breakdown);
            book.write(bytes);
            return bytes.toByteArray();
        }
    }

    /** The category table from the event page, so the workbook opens on the same numbers as the screen. */
    private static void summary(Workbook book, Styles styles, EventBudgetReport.Breakdown breakdown) {
        var sheet = book.createSheet("By category");
        widths(sheet, 34, 18, 18, 18, 18, 12);
        header(sheet, styles, 0, "Category", "Estimated", "Committed", "Paid", "Left to commit", "Used");
        var percent = book.createCellStyle();
        percent.setDataFormat(book.createDataFormat().getFormat("0%"));
        int index = 1;
        for (var category : breakdown.categories()) {
            var row = sheet.createRow(index++);
            cell(row, 0, category.category(), styles.text());
            money(row, 1, category.estimated(), styles.money());
            money(row, 2, category.committed(), styles.money());
            money(row, 3, category.paid(), styles.moneyMuted());
            money(row, 4, category.estimated().subtract(category.committed()), styles.money());
            var used = row.createCell(5);
            if (category.estimated().signum() > 0)
                used.setCellValue(category.committed().doubleValue() / category.estimated().doubleValue());
            used.setCellStyle(percent);
        }
        var total = sheet.createRow(index + 1);
        cell(total, 0, "Total", styles.totalText());
        money(total, 1, breakdown.estimated(), styles.totalMoney());
        money(total, 2, breakdown.committed(), styles.totalMoney());
        money(total, 3, breakdown.paid(), styles.totalMoney());
        money(total, 4, breakdown.estimated().subtract(breakdown.committed()), styles.totalMoney());
    }

    /** Every scenario of an event side by side — the shape the venue quotes arrive in. */
    public byte[] comparison(EventProject event) throws IOException {
        var comparison = report.comparison(event);
        int columns = comparison.scenarios().size();
        try (var book = new XSSFWorkbook(); var bytes = new ByteArrayOutputStream()) {
            var styles = styles(book);
            var sheet = book.createSheet("Scenarios");
            var widths = new int[5 + Math.max(columns, 1)];
            widths[0] = 26; widths[1] = 34; widths[2] = 58; widths[3] = 20; widths[4] = 16;
            for (int i = 5; i < widths.length; i++) widths[i] = 20;
            widths(sheet, widths);

            heading(sheet, styles, (event.getDescription() == null ? event.getCode() : event.getDescription())
                    + " — estimate scenarios", context(event), 4 + Math.max(columns, 1));

            var labels = new String[5 + columns];
            labels[0] = "Category"; labels[1] = "Position"; labels[2] = "Comments"; labels[3] = "Event day";
            labels[4] = "Charge type";
            for (int i = 0; i < columns; i++) labels[5 + i] = EventBudgetReport.label(comparison.scenarios().get(i).budget());
            header(sheet, styles, 3, labels);

            int index = 4;
            String band = null;
            for (var row : comparison.rows()) {
                if (!row.category().equals(band)) {
                    band = row.category();
                    var heading = sheet.createRow(index++);
                    cell(heading, 0, band, styles.band());
                    for (int column = 1; column < labels.length; column++) cell(heading, column, null, styles.band());
                }
                var line = sheet.createRow(index++);
                cell(line, 0, row.category(), styles.muted());
                cell(line, 1, row.article(), styles.text());
                cell(line, 2, row.details(), styles.wrapped());
                cell(line, 3, row.phaseLabel(), styles.text());
                cell(line, 4, row.kindLabel(), styles.text());
                for (int column = 0; column < columns; column++) {
                    var amount = row.amounts().get(column);
                    // An unpriced line still says something — "To be confirmed", "Included" — and the
                    // PDFs carry exactly that, so the cell shows the status rather than going blank.
                    if (amount != null) money(line, 5 + column, amount, styles.money());
                    else cell(line, 5 + column, row.states().get(column), styles.muted());
                }
            }

            var total = sheet.createRow(index + 1);
            cell(total, 0, "Total", styles.totalText());
            for (int column = 1; column < 5; column++) cell(total, column, null, styles.totalText());
            for (int column = 0; column < columns; column++)
                money(total, 5 + column, comparison.scenarios().get(column).total(), styles.totalMoney());

            int note = index + 3;
            cell(sheet.createRow(note++), 0,
                    "Only one scenario is selected for an event; alternatives are never added together.",
                    styles.subtitle());
            if (comparison.commentsDiffer()) cell(sheet.createRow(note), 0,
                    "Where venues described the same position differently, the first scenario's comment is shown; "
                    + "each scenario's own export carries its full wording.", styles.subtitle());
            book.write(bytes);
            return bytes.toByteArray();
        }
    }

    /** Excel rejects 31+ characters and the []:*?/\ set in a tab name. */
    private static String sheetName(String value) {
        var safe = value.replaceAll("[\\[\\]:*?/\\\\]", " ").trim();
        if (safe.isEmpty()) safe = "Estimate";
        return safe.length() > 31 ? safe.substring(0, 31) : safe;
    }
}
