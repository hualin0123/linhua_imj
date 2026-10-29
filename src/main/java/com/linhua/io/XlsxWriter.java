package com.linhua.io;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 极简 xlsx 写出器（仅依赖 JDK），用于把计算结果写回 Excel。
 * 复刻 Python 端 absorption / Pabs_lnG 的“追加计算列并保存为 &lt;原名&gt;(update).xlsx”。
 *
 * <p>单元格可为 {@link Number}（写 &lt;v&gt; 数值）或任意对象（转字符串，用 inlineStr 写入，
 * 无需 sharedStrings.xml）。仅支持单工作表，够本插件使用。</p>
 */
public final class XlsxWriter {

    private XlsxWriter() { }

    /** 写出到指定文件。rows 的第一行通常作为表头。 */
    public static void write(File out, List<List<Object>> rows) throws IOException {
        String sheet = buildSheet(rows);
        try (ZipOutputStream zos = new ZipOutputStream(
                new java.io.FileOutputStream(out), StandardCharsets.UTF_8)) {
            put(zos, "[Content_Types].xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                + "</Types>");
            put(zos, "_rels/.rels",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                + "</Relationships>");
            put(zos, "xl/workbook.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                + "<sheets><sheet name=\"Sheet1\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
            put(zos, "xl/_rels/workbook.xml.rels",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                + "</Relationships>");
            put(zos, "xl/worksheets/sheet1.xml", sheet);
        }
    }

    private static void put(ZipOutputStream zos, String name, String content) throws IOException {
        ZipEntry e = new ZipEntry(name);
        zos.putNextEntry(e);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        zos.write(bytes);
        zos.closeEntry();
    }

    private static String buildSheet(List<List<Object>> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
        sb.append("<sheetData>");
        int r = 1;
        for (List<Object> row : rows) {
            sb.append("<row r=\"").append(r).append("\">");
            int c = 1;
            for (Object cell : row) {
                String ref = colLetter(c) + r;
                if (cell instanceof Number) {
                    sb.append("<c r=\"").append(ref).append("\"><v>").append(cell).append("</v></c>");
                } else {
                    String s = (cell == null) ? "" : cell.toString();
                    sb.append("<c r=\"").append(ref)
                      .append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                      .append(escape(s)).append("</t></is></c>");
                }
                c++;
            }
            sb.append("</row>");
            r++;
        }
        sb.append("</sheetData></worksheet>");
        return sb.toString();
    }

    private static String colLetter(int col) {
        StringBuilder s = new StringBuilder();
        while (col > 0) {
            int rem = (col - 1) % 26;
            s.insert(0, (char) ('A' + rem));
            col = (col - 1) / 26;
        }
        return s.toString();
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&apos;"); break;
                default: sb.append(ch);
            }
        }
        return sb.toString();
    }
}
