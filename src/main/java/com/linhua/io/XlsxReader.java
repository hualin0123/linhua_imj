package com.linhua.io;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * 极简 xlsx 读取器（仅依赖 JDK，无第三方库）。
 *
 * <p>返回行列表，每行是字符串单元格（数值以文本形式保留，调用方自行解析为 double）。
 * 支持：共享字符串（sharedStrings）、数值单元格、公式缓存值、内联字符串（inlineStr）。
 * 仅读取第一个工作表（xl/worksheets/sheet1.xml，若不存在则取编号最小的 sheet）。</p>
 *
 * @author Hua Lin
 * @since 0.1.0
 */
public final class XlsxReader {

    private XlsxReader() {
    }

    public static List<List<String>> read(File file) throws Exception {
        List<String> shared = new ArrayList<>();
        List<List<String>> rows;

        try (ZipFile zf = new ZipFile(file)) {
            ZipEntry ss = zf.getEntry("xl/sharedStrings.xml");
            if (ss != null) {
                shared = parseSharedStrings(zf.getInputStream(ss));
            }
            ZipEntry sheet = zf.getEntry("xl/worksheets/sheet1.xml");
            if (sheet == null) {
                sheet = firstWorksheet(zf);
            }
            if (sheet == null) {
                throw new Exception("在 xlsx 中未找到工作表。");
            }
            rows = parseSheet(zf.getInputStream(sheet), shared);
        }
        return rows;
    }

    private static ZipEntry firstWorksheet(ZipFile zf) {
        for (int i = 1; i < 100; i++) {
            ZipEntry e = zf.getEntry("xl/worksheets/sheet" + i + ".xml");
            if (e != null) {
                return e;
            }
        }
        return null;
    }

    private static List<String> parseSharedStrings(InputStream in) throws Exception {
        List<String> list = new ArrayList<>();
        Document doc = newDocument(in);
        NodeList siList = doc.getElementsByTagName("si");
        for (int i = 0; i < siList.getLength(); i++) {
            Element si = (Element) siList.item(i);
            NodeList tList = si.getElementsByTagName("t");
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < tList.getLength(); j++) {
                sb.append(tList.item(j).getTextContent());
            }
            list.add(sb.toString());
        }
        return list;
    }

    private static List<List<String>> parseSheet(InputStream in, List<String> shared) throws Exception {
        List<List<String>> rows = new ArrayList<>();
        int maxCol = 0;
        Document doc = newDocument(in);
        NodeList rowList = doc.getElementsByTagName("row");
        for (int r = 0; r < rowList.getLength(); r++) {
            Element rowEl = (Element) rowList.item(r);
            Map<Integer, String> cells = new HashMap<>();
            NodeList cList = rowEl.getElementsByTagName("c");
            for (int c = 0; c < cList.getLength(); c++) {
                Element cEl = (Element) cList.item(c);
                String ref = cEl.getAttribute("r");
                int col = columnIndex(ref);
                String val = cellValue(cEl, cEl.getAttribute("t"), shared);
                cells.put(col, val);
                if (col + 1 > maxCol) {
                    maxCol = col + 1;
                }
            }
            List<String> row = new ArrayList<>(maxCol);
            for (int i = 0; i < maxCol; i++) {
                row.add(cells.getOrDefault(i, ""));
            }
            rows.add(row);
        }
        return rows;
    }

    private static String cellValue(Element cEl, String type, List<String> shared) {
        if ("s".equals(type)) {
            NodeList v = cEl.getElementsByTagName("v");
            if (v.getLength() > 0) {
                int idx = Integer.parseInt(v.item(0).getTextContent().trim());
                return (idx >= 0 && idx < shared.size()) ? shared.get(idx) : "";
            }
            return "";
        }
        if ("inlineStr".equals(type)) {
            NodeList t = cEl.getElementsByTagName("t");
            return t.getLength() > 0 ? t.item(0).getTextContent() : "";
        }
        // 数值（或公式缓存值）存于 <v>
        NodeList v = cEl.getElementsByTagName("v");
        return v.getLength() > 0 ? v.item(0).getTextContent() : "";
    }

    private static int columnIndex(String ref) {
        int i = 0;
        while (i < ref.length() && Character.isLetter(ref.charAt(i))) {
            i++;
        }
        String letters = ref.substring(0, i).toUpperCase();
        int col = 0;
        for (int k = 0; k < letters.length(); k++) {
            col = col * 26 + (letters.charAt(k) - 'A' + 1);
        }
        return col - 1; // 0-based
    }

    private static Document newDocument(InputStream in) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        DocumentBuilder b = f.newDocumentBuilder();
        return b.parse(in);
    }
}
