package com.linhua.gaus;

import com.linhua.plot.Plot;
import com.linhua.plot.PlotUtil;
import ij.IJ;
import ij.gui.GenericDialog;
import ij.plugin.PlugIn;
import java.awt.Color;

/** 对应 matching1.py：模匹配四解随透镜焦距 F 的变化曲线。 */
public class matching1 implements PlugIn {
    private double lambdaMm = GausCore.DEFAULT_LAMBDA_MM;

    @Override
    public void run(String arg) {
        GenericDialog gd = new GenericDialog("Mode Matching (sweep F)");
        gd.addMessage("M = P(L1)·lens(F)·P(L2)（模匹配，扫描 F）");
        gd.addNumericField("波长 (nm)", 2060, 0);
        gd.addNumericField("束腰 w1 (mm)", 0.007, 5);
        gd.addNumericField("束腰 w2 (mm)", 0.5, 4);
        gd.addNumericField("F 扫描下限 (mm)", 12.4, 4);
        gd.addNumericField("F 扫描上限 (mm)", 12.6, 4);
        gd.addNumericField("采样点数", 300, 0);
        gd.showDialog();
        if (gd.wasCanceled()) {
            return;
        }
        lambdaMm = gd.getNextNumber() * 1e-6;
        double w1 = gd.getNextNumber();
        double w2 = gd.getNextNumber();
        double fmin = gd.getNextNumber();
        double fmax = gd.getNextNumber();
        if (fmax <= fmin) {
            IJ.error("参数无效：需 F上限>F下限");
            return;
        }
        int n = 300;
        double[] x = new double[n];
        double[] L1 = new double[n], L2 = new double[n], La = new double[n], Lb = new double[n];
        for (int i = 0; i < n; i++) {
            double F = fmin + (fmax - fmin) * i / (n - 1);
            double[] r = GausCore.modeMatching(w1, w2, F, lambdaMm);
            x[i] = F;
            L1[i] = r[0]; L2[i] = r[1]; La[i] = r[2]; Lb[i] = r[3];
        }
        Plot plot = new Plot("Mode Matching", "F / mm", "distance / mm");
        PlotUtil.applyStyle(plot, "F / mm", "distance / mm", 4, 3,
                Plot.LEGEND_UPPER_RIGHT, true, false, true);
        GausUtil.addSplitLine(plot, x, L1, Color.RED, "L1");
        GausUtil.addSplitLine(plot, x, L2, Color.BLUE, "L2");
        GausUtil.addSplitLine(plot, x, La, Color.GREEN, "La");
        GausUtil.addSplitLine(plot, x, Lb, Color.MAGENTA, "Lb");
        plot.setNorthAnnotation("w1=" + GausUtil.fmt(w1) + ", w2=" + GausUtil.fmt(w2)
                + " mm, λ=" + GausUtil.fmt(lambdaMm * 1e6) + " nm");
        plot.showPlot();
        IJ.log("=== Mode Matching (sweep F) ===\nw1=" + GausUtil.fmt(w1) + ", w2=" + GausUtil.fmt(w2)
                + " mm, λ=" + GausUtil.fmt(lambdaMm * 1e6) + " nm\n");
    }
}
