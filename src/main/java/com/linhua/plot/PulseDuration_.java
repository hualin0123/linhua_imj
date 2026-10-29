package com.linhua.plot;

import ij.IJ;
import ij.plugin.PlugIn;
import java.awt.Color;
import java.io.File;
import java.util.ArrayList;
import com.linhua.io.Table;

/**
 * 脉冲时长图（归一化轨迹）：列按 x1,图例1,x2,图例2,… 交错；每条强度列做 min-max 归一化，
 * 按时间跨度自动换算 fs/ps/ns。复刻 Python _250304_plot_pulse_duration.py（仅画归一化线，不含拟合曲线）。
 */
public class PulseDuration_ implements PlugIn {

    private static final String X_LABEL = "Time (fs/ps/ns)";
    private static final String Y_LABEL = "Intensity (a.u)";

    @Override
    public void run(String arg) {
        File[] files = PlotUtil.chooseFiles("选择脉冲时长数据 (xlsx/csv)", "csv", "xlsx");
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

    static double[] timeUnit(double tMax) {
        if (tMax <= 2e-12) {
            return new double[]{1e15, 0};
        } else if (tMax <= 2e-9) {
            return new double[]{1e12, 1};
        }
        return new double[]{1e9, 2};
    }

    static final String[] UNITS = {"fs", "ps", "ns"};

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

        double tMax = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < nSeries; i++) {
            for (int r = 1; r < t.rowCount(); r++) {
                double v = t.num(r, 2 * i);
                if (!Double.isNaN(v) && v > tMax) {
                    tMax = v;
                }
            }
        }
        double[] u = timeUnit(tMax);
        double factor = u[0];
        String unit = UNITS[(int) u[1]];

        Plot plot = new Plot(f.getName(), "Time (" + unit + ")", Y_LABEL);
        PlotUtil.applyStyle(plot, "Time (" + unit + ")", Y_LABEL, 0, 1, Plot.LEGEND_UPPER_LEFT, true, false, true);

        double xmin = Double.POSITIVE_INFINITY, xmax = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < nSeries; i++) {
            ArrayList<Double> Xs = new ArrayList<>(), Ys = new ArrayList<>();
            for (int r = 1; r < t.rowCount(); r++) {
                double xv = t.num(r, 2 * i), yv = t.num(r, 2 * i + 1);
                if (!Double.isNaN(xv) && !Double.isNaN(yv)) {
                    Xs.add(xv * factor);
                    Ys.add(yv);
                }
            }
            double[] x = toArr(Xs), y = toArr(Ys);
            double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
            for (double v : y) {
                if (v < lo) lo = v;
                if (v > hi) hi = v;
            }
            if (hi > lo) {
                for (int k = 0; k < y.length; k++) {
                    y[k] = (y[k] - lo) / (hi - lo);
                }
            }
            for (double v : x) {
                if (v < xmin) xmin = v;
                if (v > xmax) xmax = v;
            }
            plot.addLine(x, y, colors[i], t.header(2 * i + 1), 0.8f);
        }

        if (xmax > xmin) {
            double pad = (xmax - xmin) * 0.02;
            plot.setXLim(xmin - pad, xmax + pad);
        }
        plot.setYLim(-0.02, 1.05);
        plot.showPlot();
    }

    static double[] toArr(ArrayList<Double> l) {
        double[] a = new double[l.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = l.get(i);
        }
        return a;
    }
}
