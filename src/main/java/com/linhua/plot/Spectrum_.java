package com.linhua.plot;

import ij.IJ;
import ij.plugin.PlugIn;
import java.awt.Color;
import java.io.File;
import java.util.ArrayList;
import com.linhua.io.Table;

/**
 * 光谱图：列按 x1,图例1,x2,图例2,… 交错；每条强度列做 min-max 归一化后画线（无标记）。
 * 复刻 Python _250227_plot_spectrum.py（单文件）。
 */
public class Spectrum_ implements PlugIn {

    private static final String X_LABEL = "Wavelength (nm)";
    private static final String Y_LABEL = "Intensity (a.u)";

    @Override
    public void run(String arg) {
        File[] files = PlotUtil.chooseFiles("选择光谱数据 (xlsx/csv)", "csv", "xlsx");
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

        Plot plot = new Plot(f.getName(), X_LABEL, Y_LABEL);
        PlotUtil.applyStyle(plot, X_LABEL, Y_LABEL, 0, 1, Plot.LEGEND_UPPER_LEFT, true, false, true);

        double xmin = Double.POSITIVE_INFINITY, xmax = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < nSeries; i++) {
            int xc = 2 * i, yc = 2 * i + 1;
            ArrayList<Double> Xraw = new ArrayList<>(), Yraw = new ArrayList<>();
            for (int r = 1; r < t.rowCount(); r++) {
                double xv = t.num(r, xc), yv = t.num(r, yc);
                if (!Double.isNaN(xv) && !Double.isNaN(yv)) {
                    Xraw.add(xv);
                    Yraw.add(yv);
                }
            }
            double[] x = toArr(Xraw);
            double[] y = toArr(Yraw);
            // min-max 归一化
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
            plot.addLine(x, y, colors[i], t.header(yc), 0.8f);
        }

        if (xmin < xmax) {
            double pad = (xmax - xmin) * 0.02;
            plot.setXLim(xmin - pad, xmax + pad);
        }
        plot.setYLim(-0.02, 1.05);
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
