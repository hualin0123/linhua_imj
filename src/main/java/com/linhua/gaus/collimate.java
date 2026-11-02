package com.linhua.gaus;

import com.linhua.plot.Plot;
import com.linhua.plot.PlotUtil;
import ij.IJ;
import ij.gui.GenericDialog;
import ij.plugin.PlugIn;
import java.awt.Color;

/** 对应 collimate.py：准直后腰斑 w1 = w0·F/f，其中 f = π·w0²/λ，扫描准直透镜焦距 F。 */
public class collimate implements PlugIn {
    private double lambdaMm = GausCore.DEFAULT_LAMBDA_MM;

    @Override
    public void run(String arg) {
        GenericDialog gd = new GenericDialog("Collimation");
        gd.addMessage("M = 薄透镜 M(F)=[1,0;-1/F,1]; w1=w0·F/f, f=πw0²/λ");
        gd.addNumericField("波长 (nm)", 2060, 0);
        gd.addNumericField("入射束腰 w0 (mm)", 0.1, 4);
        gd.addNumericField("F 扫描下限 (mm)", 5, 1);
        gd.addNumericField("F 扫描上限 (mm)", 50, 1);
        gd.addNumericField("采样点数", 200, 0);
        gd.showDialog();
        if (gd.wasCanceled()) {
            return;
        }
        lambdaMm = gd.getNextNumber() * 1e-6;
        double w0 = gd.getNextNumber();
        double lo = gd.getNextNumber();
        double hi = gd.getNextNumber();
        int n = (int) gd.getNextNumber();
        if (n < 2 || hi <= lo) {
            IJ.error("参数无效：需 hi>lo 且采样点数≥2");
            return;
        }
        double[] x = new double[n], y = new double[n];
        for (int i = 0; i < n; i++) {
            double F = lo + (hi - lo) * i / (n - 1);
            x[i] = F;
            y[i] = GausCore.collimateW(w0, F, lambdaMm);
        }
        Plot plot = new Plot("Collimation", "F / mm", "w1 / mm");
        PlotUtil.applyStyle(plot, "F / mm", "w1 / mm", 1, 5,
                Plot.LEGEND_UPPER_LEFT, true, false, true);
        GausUtil.addSplitLine(plot, x, y, Color.RED, "w1");
        plot.setNorthAnnotation("w0=" + GausUtil.fmt(w0) + " mm, λ=" + GausUtil.fmt(lambdaMm * 1e6) + " nm");
        plot.showPlot();
        IJ.log("=== Collimation ===\nw0=" + GausUtil.fmt(w0) + " mm, λ=" + GausUtil.fmt(lambdaMm * 1e6)
                + " nm\nf (Rayleigh)=" + GausUtil.fmt(GausCore.rayleigh(w0, lambdaMm)) + " mm\n");
    }
}
