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
 * 脉冲时长图（含拟合）：列按 x1,图例1,x2,图例2,… 交错；每条强度列归一化后拟合 gaussian/sech2，
 * 画空心散点 + 拟合曲线，图例标注 FWHM。复刻 Python _210918_plot_pulse_duration.py。
 */
public class PulseDuration1_ implements PlugIn {

    private static final String[] SHAPES = {"gaussian", "sech2"};

    @Override
    public void run(String arg) {
        File[] files = PlotUtil.chooseFiles("选择脉冲时长数据 (xlsx/csv)", "csv", "xlsx");
        if (files.length == 0) {
            return;
        }
        GenericDialog gd = new GenericDialog("脉冲时长（拟合）");
        gd.addChoice("脉冲形状", SHAPES, "gaussian");
        gd.showDialog();
        if (gd.wasCanceled()) {
            return;
        }
        String shape = SHAPES[gd.getNextChoiceIndex()];
        for (File f : files) {
            try {
                plotFile(f, shape);
            } catch (Exception e) {
                IJ.log("处理失败: " + f.getName() + " - " + e.getMessage());
            }
        }
    }

    private static void plotFile(File f, String shape) throws Exception {
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

        double tMax = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < nSeries; i++) {
            for (int r = 1; r < t.rowCount(); r++) {
                double v = t.num(r, 2 * i);
                if (!Double.isNaN(v) && v > tMax) {
                    tMax = v;
                }
            }
        }
        double[] u = PulseDuration_.timeUnit(tMax);
        double factor = u[0];
        String unit = PulseDuration_.UNITS[(int) u[1]];

        Plot plot = new Plot(f.getName(), "Time (" + unit + ")", "Intensity (a.u)");
        PlotUtil.applyStyle(plot, "Time (" + unit + ")", "Intensity (a.u)", 0, 1, Plot.LEGEND_UPPER_LEFT, true, false, true);

        for (int i = 0; i < nSeries; i++) {
            ArrayList<Double> Xs = new ArrayList<>(), Ys = new ArrayList<>();
            for (int r = 1; r < t.rowCount(); r++) {
                double xv = t.num(r, 2 * i), yv = t.num(r, 2 * i + 1);
                if (!Double.isNaN(xv) && !Double.isNaN(yv)) {
                    Xs.add(xv * factor);
                    Ys.add(yv);
                }
            }
            double[] x = PulseDuration_.toArr(Xs), y = PulseDuration_.toArr(Ys);
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
            CurveFitter.Model model = shape.equals("sech2") ? Models.sech2() : Models.gaussian();
            double[] rng = {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
            for (double v : x) {
                if (v < rng[0]) rng[0] = v;
                if (v > rng[1]) rng[1] = v;
            }
            double g = (rng[1] - rng[0]) / 3.0;
            double[] p0 = {g > 0 ? g : 1.0};
            double fwhm = p0[0];
            try {
                double[] pf = CurveFitter.fit(x, y, model, p0);
                fwhm = pf[0];
            } catch (Exception e) {
                IJ.log("拟合失败: " + e.getMessage());
            }
            double[] fitted = new double[x.length];
            for (int k = 0; k < x.length; k++) {
                fitted[k] = model.value(x[k], new double[]{fwhm});
            }
            String leg = t.header(2 * i + 1) + ", " + String.format("%.1f", fwhm) + " " + unit;
            plot.addLine(x, fitted, colors[i], null, 2.4f);
            plot.addScatter(x, y, colors[i], leg, markers[i], true, mr, 1.5f);
        }
        plot.showPlot();
    }
}
