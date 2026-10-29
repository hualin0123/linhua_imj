package com.linhua.plot;

import ij.IJ;
import ij.gui.GenericDialog;
import ij.plugin.PlugIn;
import java.awt.Color;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import com.linhua.io.Table;
import com.linhua.io.XlsxWriter;

/**
 * 吸收：列按 x1,混偏,x2,π偏,x3,σ偏 交错。第一张图 = 吸收效率(%) vs 输入功率；
 * 第二张图 = 吸收功率密度(α·exp(-αl)·x_max) vs 晶体位置。并写回 x4、π偏+σ偏 两列。
 * 复刻 Python _250423_plot_absorption.py（单文件）。
 */
public class Absorption_ implements PlugIn {

    @Override
    public void run(String arg) {
        File[] files = PlotUtil.chooseFiles("选择吸收数据 (xlsx/csv)", "csv", "xlsx");
        if (files.length == 0) {
            return;
        }
        GenericDialog gd = new GenericDialog("吸收参数");
        gd.addNumericField("crystal_length (mm)", 50, 1);
        gd.showDialog();
        if (gd.wasCanceled()) {
            return;
        }
        double l = gd.getNextNumber();
        for (File f : files) {
            try {
                plotFile(f, l);
            } catch (Exception e) {
                IJ.error("解析失败: " + f.getName(), e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private static void plotFile(File f, double l) throws Exception {
        Table t = Table.read(f);
        int ncol = t.colCount();
        int nSeries = ncol / 2;
        if (nSeries < 1) {
            IJ.error("至少需要两列（x1, y1）。");
            return;
        }
        Color[] colors = PlotUtil.assignColors(nSeries);
        int[] markers = PlotUtil.assignMarkers(nSeries);
        float mr = PlotUtil.markerRadius(t.rowCount() - 1);

        // ---------- 图 1：吸收效率 vs 输入功率 ----------
        Plot plotEff = new Plot(f.getName() + " - 吸收效率", "Input power (W)", "Absorption efficiency (%)");
        PlotUtil.applyStyle(plotEff, "Input power (W)", "Absorption efficiency (%)",
                0, 1, Plot.LEGEND_UPPER_RIGHT, true, false, true);

        // ---------- 图 2：吸收功率密度 vs 位置 ----------
        Plot plotLen = new Plot(f.getName() + " - 吸收功率密度", "Position (mm)", "Absorbed power per length (W)");
        PlotUtil.applyStyle(plotLen, "Position (mm)", "Absorbed power per length (W)",
                0, 1, Plot.LEGEND_UPPER_RIGHT, true, false, true);

        for (int i = 0; i < nSeries; i++) {
            int xc = 2 * i, yc = 2 * i + 1;
            ArrayList<Double> X = new ArrayList<>(), Y = new ArrayList<>();
            for (int r = 1; r < t.rowCount(); r++) {
                double xv = t.num(r, xc), yv = t.num(r, yc);
                if (!Double.isNaN(xv) && !Double.isNaN(yv)) {
                    X.add(xv);
                    Y.add(yv);
                }
            }
            double[] x = toArr(X), y = toArr(Y);
            // 吸收效率
            double[] eff = new double[x.length];
            for (int k = 0; k < x.length; k++) {
                eff[k] = (x[k] != 0) ? (x[k] - y[k]) / x[k] * 100.0 : 0;
            }
            // 吸收系数 alpha
            double ymax = max(y), xmax = max(x);
            double alpha = (ymax > 0 && xmax > 0) ? -0.5 * Math.log(ymax / xmax) / l : 0;
            int m = 100;
            double[] ll = new double[m];
            double[] absorbed = new double[m];
            for (int k = 0; k < m; k++) {
                ll[k] = l * k / (m - 1);
                absorbed[k] = alpha * Math.exp(-alpha * ll[k]) * xmax;
            }

            plotEff.addLine(x, eff, colors[i], null, 2.4f);
            plotEff.addScatter(x, eff, colors[i], t.header(yc), markers[i], true, mr, 2.0f);
            plotLen.addLine(ll, absorbed, colors[i], t.header(yc), 2.4f);
        }
        plotEff.showPlot();
        plotLen.showPlot();

        writeBack(f, t);
    }

    private static double max(double[] a) {
        double hi = Double.NEGATIVE_INFINITY;
        for (double v : a) {
            if (v > hi) hi = v;
        }
        return hi;
    }

    private static void writeBack(File f, Table t) {
        try {
            int ncol = t.colCount();
            int nSeries = ncol / 2;
            if (nSeries < 3) {
                return; // 需要 x2,x3,π偏,σ偏 才能算 x4 和 π偏+σ偏
            }
            double[] x2 = t.column(2);
            double[] x3 = t.column(4);
            double[] pi = t.column(3);
            double[] sig = t.column(5);
            int nrows = t.rowCount();
            List<List<Object>> out = new ArrayList<>(nrows);
            for (int r = 0; r < nrows; r++) {
                List<Object> row = new ArrayList<>(t.rows().get(r));
                if (r == 0) {
                    row.add("x4");
                    row.add("π偏+σ偏");
                } else {
                    int idx = r - 1;
                    double x4 = (idx < x2.length && idx < x3.length) ? x2[idx] + x3[idx] : Double.NaN;
                    double ps = (idx < pi.length && idx < sig.length) ? pi[idx] + sig[idx] : Double.NaN;
                    row.add(x4);
                    row.add(ps);
                }
                out.add(row);
            }
            String name = f.getName();
            int dot = name.lastIndexOf('.');
            String base = (dot > 0) ? name.substring(0, dot) : name;
            File outFile = new File(f.getParent(), base + "(update).xlsx");
            XlsxWriter.write(outFile, out);
            IJ.log("已写回: " + outFile.getAbsolutePath());
        } catch (Exception e) {
            IJ.log("写回失败: " + e.getMessage());
        }
    }

    private static double[] toArr(ArrayList<Double> l) {
        double[] a = new double[l.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = l.get(i);
        }
        return a;
    }
}
