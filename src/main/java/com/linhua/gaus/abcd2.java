package com.linhua.gaus;

import com.linhua.plot.Plot;
import com.linhua.plot.PlotUtil;
import ij.IJ;
import ij.gui.GenericDialog;
import ij.plugin.PlugIn;
import java.awt.Color;

/** 对应 abcd2.py：f·P(L)·M(R)·P(L)·f，扫描薄透镜焦距 f。 */
public class abcd2 implements PlugIn {
    private double lambdaMm = GausCore.DEFAULT_LAMBDA_MM;

    @Override
    public void run(String arg) {
        GenericDialog gd = new GenericDialog("ABCD2 (scan f)");
        gd.addMessage("M = f·P(L)·M(R)·P(L)·f");
        gd.addNumericField("波长 (nm)", 2060, 0);
        gd.addNumericField("反射镜曲率半径 R (mm)", 500, 1);
        gd.addNumericField("长度 L (mm)", 450, 1);
        gd.addNumericField("f 扫描下限 (mm)", -2000, 1);
        gd.addNumericField("f 扫描上限 (mm)", 2000, 1);
        gd.addNumericField("采样点数", 600, 0);
        gd.showDialog();
        if (gd.wasCanceled()) {
            return;
        }
        lambdaMm = gd.getNextNumber() * 1e-6;
        double R = gd.getNextNumber();
        double L = gd.getNextNumber();
        double lo = gd.getNextNumber();
        double hi = gd.getNextNumber();
        int n = (int) gd.getNextNumber();
        if (n < 2 || hi <= lo) {
            IJ.error("参数无效：需 hi>lo 且采样点数≥2");
            return;
        }
        double[] x = new double[n], w = new double[n];
        for (int i = 0; i < n; i++) {
            double f = lo + (hi - lo) * i / (n - 1);
            GausCore.Mat m = GausCore.Mat.lens(f).mul(GausCore.Mat.propagation(L))
                    .mul(GausCore.Mat.mirror(R)).mul(GausCore.Mat.propagation(L))
                    .mul(GausCore.Mat.lens(f));
            x[i] = f;
            w[i] = GausCore.waistFromAbcd(m, lambdaMm);
        }
        String stable = GausUtil.stableIntervals(x, w);
        Plot plot = new Plot("ABCD2 (scan f)", "f / mm", "w0 / mm");
        PlotUtil.applyStyle(plot, "f / mm", "w0 / mm", 0, 4,
                Plot.LEGEND_UPPER_RIGHT, true, false, true);
        GausUtil.addSplitLine(plot, x, w, Color.BLUE, "waist w0");
        plot.setNorthAnnotation("R=" + GausUtil.fmt(R) + ", L=" + GausUtil.fmt(L)
                + " mm, λ=" + GausUtil.fmt(lambdaMm * 1e6) + " nm");
        plot.setAnnotation("stable: " + stable);
        plot.showPlot();
        IJ.log("=== ABCD2 (scan f) ===\nR=" + GausUtil.fmt(R) + ", L=" + GausUtil.fmt(L)
                + " mm\nstable f: " + stable + " mm\n");
    }
}
