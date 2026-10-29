package com.linhua.io;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 对 XlsxReader / CSV 读出的二维表格的轻量封装，便于各绘图插件按列取数。
 * 约定第 0 行为表头，数据从第 1 行开始。
 */
public final class Table {

    private final List<List<String>> rows;

    private Table(List<List<String>> rows) {
        this.rows = rows;
    }

    public static Table read(File f) throws Exception {
        String lower = f.getName().toLowerCase();
        List<List<String>> tbl;
        if (lower.endsWith(".xlsx") || lower.endsWith(".xlsm")) {
            tbl = XlsxReader.read(f);
        } else {
            tbl = readCsv(f);
        }
        return new Table(tbl);
    }

    private static List<List<String>> readCsv(File f) throws Exception {
        List<List<String>> tbl = new ArrayList<>();
        BufferedReader br = new BufferedReader(new FileReader(f));
        String line;
        while ((line = br.readLine()) != null) {
            String[] v = line.split(",");
            List<String> row = new ArrayList<>(v.length);
            for (String s : v) {
                row.add(s.trim());
            }
            tbl.add(row);
        }
        br.close();
        return tbl;
    }

    public int rowCount() {
        return rows.size();
    }

    public int colCount() {
        return rows.isEmpty() ? 0 : rows.get(0).size();
    }

    public String cell(int r, int c) {
        if (r < 0 || r >= rows.size()) {
            return "";
        }
        List<String> row = rows.get(r);
        if (c < 0 || c >= row.size()) {
            return "";
        }
        return row.get(c);
    }

    public double num(int r, int c) {
        String s = cell(r, c).trim();
        if (s.isEmpty()) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    public String header(int c) {
        return cell(0, c);
    }

    /** 返回底层二维字符串表格（写回 Excel 时使用）。 */
    public List<List<String>> rows() {
        return rows;
    }

    /** 取第 c 列（跳过表头）的数值，自动丢弃空/非数值单元格。 */
    public double[] column(int c) {
        return columnFrom(c, 1);
    }

    public double[] columnFrom(int c, int startRow) {
        ArrayList<Double> out = new ArrayList<>();
        for (int r = startRow; r < rows.size(); r++) {
            double v = num(r, c);
            if (!Double.isNaN(v)) {
                out.add(v);
            }
        }
        return toArray(out);
    }

    private static double[] toArray(ArrayList<Double> list) {
        double[] a = new double[list.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = list.get(i);
        }
        return a;
    }
}
