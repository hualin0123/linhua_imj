package com.linhua.gaus;

import com.linhua.plot.Plot;
import com.linhua.plot.PlotUtil;
import ij.IJ;
import ij.gui.GenericDialog;
import ij.plugin.PlugIn;
import java.awt.Color;

/** 对应 abcd5.py：与 abcd4 同矩阵 f·P(L2)·P(L2)·f·P(L1)·M(R)·P(L1)，采用其默认参数。 */
public class abcd5 implements PlugIn {
    private double lambdaMm = GausCore.DEFAULT_LAMBDA_MM;

    @Override
    public void run(String arg) {
        GenericDialog gd = new GenericDialog("ABCD5 (scan f)");
        gd.addMessage("M = f·P(L2)·P(L2)·f·P(L1)·M(R)·P(L1)");
        gd.addNumericField("波长 (nm)", 2060, 0);
        gd.addNumericField("反射镜曲率半径 R (mm)", 120, 1);
        gd.addNumericField("长度 L1 (mm)", 20, 1);
        gd.addNumericField("长度 L2 (mm)", 500, 1);
        gd.addNumericField("f 扫描下限 (mm)", -123, 1);
        gd.addNumericField("f 扫描上限 (mm)", -102, 1);
        gd.addNumericField("采样点数", 600, 0);
        gd.showDialog();
        if (gd.wasCanceled()) {
            return;
        }
        lambdaMm = gd.getNextNumber() * 1e-6;
        double R = gd.getNextNumber();
        double L1 = gd.getNextNumber();
        double L2 = gd.getNextNumber();
        double lo = gd.getNextNumber();
        double hi = gd.getNextNumber();
        int n = (int) gd.getNextNumber();
        if (n < 2 || hi <= lo) {
            IJ.error("参数无效：需 hi>lo 且采样点数≥2");
            return;
        }
        GausCore.Mat mL1 = GausCore.Mat.propagation(L1);
        GausCore.Mat mL2 = GausCore.Mat.propagation(L2);
        double[] x = new double[n], w = new double[n];
        for (int i = 0; i < n; i++) {
            double f = lo + (hi - lo) * i / (n - 1);
            GausCore.Mat m = GausCore.Mat.lens(f).mul(mL2).mul(mL2)
                    .mul(GausCore.Mat.lens(f)).mul(mL1).mul(GausCore.Mat.mirror(R)).mul(mL1);
            x[i] = f;
            w[i] = GausCore.waistFromAbcd(m, lambdaMm);
        }
        String stable = GausUtil.stableIntervals(x, w);
        Plot plot = new Plot("ABCD5 (scan f)", "f / mm", "w0 / mm");
        PlotUtil.applyStyle(plot, "f / mm", "w0 / mm", 0, 4,
                Plot.LEGEND_UPPER_RIGHT, true, false, true);
        GausUtil.addSplitLine(plot, x, w, Color.BLUE, "waist w0");
        plot.setNorthAnnotation("R=" + GausUtil.fmt(R) + ", L1=" + GausUtil.fmt(L1)
                + ", L2=" + GausUtil.fmt(L2) + " mm, λ=" + GausUtil.fmt(lambdaMm * 1e6) + " nm");
        plot.setAnnotation("stable: " + stable);
        plot.showPlot();
        IJ.log("=== ABCD5 (scan f) ===\nR=" + GausUtil.fmt(R) + ", L1=" + GausUtil.fmt(L1)
                + ", L2=" + GausUtil.fmt(L2) + " mm\nstable f: " + stable + " mm\n");
    }
}
