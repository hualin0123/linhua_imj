package com.linhua.plot;

import ij.IJ;
import ij.plugin.PlugIn;
import java.awt.Color;
import java.io.File;
import java.util.ArrayList;
import com.linhua.io.Table;

/**
 * 功率稳定性：列按 x1,图例1,x2,图例2,… 交错；按时间跨度自动换算 second/min/hour，
 * 在图例标注每组的 RMS 抖动百分比。复刻 Python _250227_plot_power_stability.py（单文件）。
 */
public class PowerStability_ implements PlugIn {

    @Override
    public void run(String arg) {
        File[] files = PlotUtil.chooseFiles("选择功率稳定性数据 (xlsx/csv)", "csv", "xlsx");
        if (files.length == 0) {
            return;
        }
        for (File f : files) {
            try {
                plotFile(f);
            } catch (Exception e) {
                IJ.log("处理失败: " + f.getName() + " - " + e.getMessage());
            }
        }
    }

    private static void plotFile(File f) throws Exception {
        Table t = Table.read(f);
        int ncol = t.colCount();
        int nSeries = ncol / 2;
        if (nSeries < 1) {
            IJ.error("至少需要两列（x1, y1）。");
            return;
        }
        Color[] colors = PlotUtil.assignColors(nSeries);
        int[] markers = PlotUtil.assignMarkers(nSeries);

        // 时间单位换算（基于最大 X 值）
        double tMax = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < nSeries; i++) {
            for (int r = 1; r < t.rowCount(); r++) {
                double v = t.num(r, 2 * i);
                if (!Double.isNaN(v) && v > tMax) {
                    tMax = v;
                }
            }
        }
        double factor;
        String unit;
        if (tMax <= 120) {
            factor = 1.0;
            unit = "second";
        } else if (tMax <= 7200) {
            factor = 1.0 / 60.0;
            unit = "min";
        } else {
            factor = 1.0 / 3600.0;
            unit = "hour";
        }

        String xLabel = "Time (" + unit + ")";
        String yLabel = "Power (W)";
        Plot plot = new Plot(f.getName(), xLabel, yLabel);
        PlotUtil.applyStyle(plot, xLabel, yLabel, 0, 0, Plot.LEGEND_UPPER_RIGHT, true, false, true);

        double xmin = Double.POSITIVE_INFINITY, xmax = Double.NEGATIVE_INFINITY;
        double ymin = Double.POSITIVE_INFINITY, ymax = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < nSeries; i++) {
            int xc = 2 * i, yc = 2 * i + 1;
            ArrayList<Double> Xs = new ArrayList<>(), Ys = new ArrayList<>();
            for (int r = 1; r < t.rowCount(); r++) {
                double xv = t.num(r, xc), yv = t.num(r, yc);
                if (!Double.isNaN(xv) && !Double.isNaN(yv)) {
                    Xs.add(xv * factor);
                    Ys.add(yv);
                }
            }
            double[] x = toArr(Xs), y = toArr(Ys);
            double mean = 0, rms = 0;
            if (y.length > 0) {
                double s = 0;
                for (double v : y) {
                    s += v;
                }
                mean = s / y.length;
                double v2 = 0;
                for (double v : y) {
                    v2 += (v - mean) * (v - mean);
                }
                double std = Math.sqrt(v2 / y.length);
                rms = (mean != 0) ? std / mean * 100.0 : 0;
            }
            String leg = t.header(yc) + " RMS抖动 = " + String.format("%.1f", rms) + "%";
            for (double v : x) {
                if (v < xmin) xmin = v;
                if (v > xmax) xmax = v;
            }
            for (double v : y) {
                if (v < ymin) ymin = v;
                if (v > ymax) ymax = v;
            }
            plot.addLine(x, y, colors[i], leg, 1.2f);
        }

        if (xmax > xmin) {
            double pad = (xmax - xmin) * 0.02;
            plot.setXLim(xmin - pad, xmax + pad);
        }
        if (ymax > ymin) {
            double pad = (ymax - ymin) * 0.05;
            plot.setYLim(ymin - pad, ymax + pad);
        }
        plot.showPlot();
    }

    private static double[] toArr(ArrayList<Double> l) {
        double[] a = new double[l.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = l.get(i);
        }
        return a;
    }
}
