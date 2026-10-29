package com.linhua.plot;

import ij.IJ;
import ij.gui.GenericDialog;
import ij.plugin.PlugIn;
import java.awt.Color;
import java.io.File;
import java.util.ArrayList;
import com.linhua.fit.CurveFitter;
import com.linhua.fit.Models;
import com.linhua.io.Table;

/**
 * 多组拟合（同一 X）：第 0 列为 X，其余各列为待拟合系列，分别拟合 gaussian/sech2/small_G，
 * 画拟合曲线（实线无标记）+ 空心数据点。复刻 _240830_plot_multifitting1.py。多选对话框（每个文件一张图）。
 */
public class Multifitting1_ implements PlugIn {

    private static final String[] FUNCS = {"gaussian", "sech2", "small_G"};

    @Override
    public void run(String arg) {
        File[] files = PlotUtil.chooseFiles("选择拟合数据 (xlsx/csv)", "csv", "xlsx");
        if (files.length == 0) {
            return;
        }
        GenericDialog gd = new GenericDialog("多组拟合（同 X）");
        gd.addStringField("X 轴标签", "X title");
        gd.addStringField("Y 轴标签", "Y title");
        gd.addChoice("拟合函数", FUNCS, "gaussian");
        gd.showDialog();
        if (gd.wasCanceled()) {
            return;
        }
        String xLabel = gd.getNextString();
        String yLabel = gd.getNextString();
        String func = FUNCS[gd.getNextChoiceIndex()];
        for (File f : files) {
            try {
                plotFile(f, xLabel, yLabel, func);
            } catch (Exception e) {
                IJ.log("处理失败: " + f.getName() + " - " + e.getMessage());
            }
        }
    }

    private static void plotFile(File f, String xLabel, String yLabel, String func) throws Exception {
        Table t = Table.read(f);
        int ncol = t.colCount();
        int nSeries = ncol - 1;
        if (nSeries < 1) {
            IJ.log(f.getName() + ": 至少需要两列（x + 一个或多个 y）。");
            return;
        }
        Color[] colors = PlotUtil.assignColors(nSeries);
        int[] markers = PlotUtil.assignMarkers(nSeries);
        float mr = PlotUtil.markerRadius(t.rowCount() - 1);

        Plot plot = new Plot(f.getName(), xLabel, yLabel);
        PlotUtil.applyStyle(plot, xLabel, yLabel, 0, 2, Plot.LEGEND_UPPER_LEFT, true, false, true);

        for (int i = 1; i < ncol; i++) {
            Pair p = pair(t, 0, i);
            double[] x = p.x, y = p.y;
            if (x.length == 0) {
                continue;
            }
            Color c = colors[i - 1];
            int m = markers[i - 1];
            boolean isSmallG = func.equals("small_G");
            double[] yy = isSmallG ? toLog(y) : y;
            CurveFitter.Model model;
            double[] p0;
            if (isSmallG) {
                model = Models.smallG();
                p0 = new double[]{1.0, 0.0};
            } else {
                model = func.equals("sech2") ? Models.sech2() : Models.gaussian();
                double[] rng = range(x);
                double g = (rng[1] - rng[0]) / 3.0;
                p0 = new double[]{g > 0 ? g : 1.0};
            }
            String paramStr = "";
            double[] pf = null;
            try {
                pf = CurveFitter.fit(x, yy, model, p0);
            } catch (Exception e) {
                IJ.log(f.getName() + " 拟合失败: " + e.getMessage());
            }
            double[] fitted = new double[x.length];
            if (pf != null) {
                for (int k = 0; k < x.length; k++) {
                    fitted[k] = model.value(x[k], pf);
                }
                if (isSmallG) {
                    paramStr = "g=" + PlotUtil.formatNumber(pf[0]) + ", b=" + PlotUtil.formatNumber(pf[1]);
                } else {
                    paramStr = "fwhm=" + PlotUtil.formatNumber(pf[0]);
                }
            } else {
                System.arraycopy(yy, 0, fitted, 0, x.length);
            }
            String leg = t.header(i) + (paramStr.isEmpty() ? "" : " (" + paramStr + ")");
            plot.addLine(x, fitted, c, null, 2.4f);
            plot.addScatter(x, isSmallG ? yy : y, c, leg, m, true, mr, 0.8f);
        }
        plot.showPlot();
    }

    private static Pair pair(Table t, int xc, int yc) {
        ArrayList<Double> X = new ArrayList<>(), Y = new ArrayList<>();
        for (int r = 1; r < t.rowCount(); r++) {
            double xv = t.num(r, xc), yv = t.num(r, yc);
            if (!Double.isNaN(xv) && !Double.isNaN(yv)) {
                X.add(xv);
                Y.add(yv);
            }
        }
        return new Pair(toArr(X), toArr(Y));
    }

    private static double[] toLog(double[] y) {
        double[] o = new double[y.length];
        for (int i = 0; i < y.length; i++) {
            o[i] = Math.log(Math.max(y[i], 1e-12));
        }
        return o;
    }

    private static double[] range(double[] a) {
        double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        for (double v : a) {
            if (v < lo) lo = v;
            if (v > hi) hi = v;
        }
        return new double[]{lo, hi};
    }

    private static double[] toArr(ArrayList<Double> l) {
        double[] a = new double[l.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = l.get(i);
        }
        return a;
    }

    private static class Pair {
        double[] x, y;
        Pair(double[] x, double[] y) { this.x = x; this.y = y; }
    }
}
