package com.linhua.gaus;

import com.linhua.plot.Plot;
import com.linhua.plot.PlotUtil;
import ij.IJ;
import ij.gui.GenericDialog;
import ij.plugin.PlugIn;
import java.awt.Color;

/** 对应 abcd1.py：含单个反射镜的环形腔，扫描 L 求稳定区间与基模腰斑 w0。 */
public class abcd1 implements PlugIn {
    private double lambdaMm = GausCore.DEFAULT_LAMBDA_MM;

    @Override
    public void run(String arg) {
        GenericDialog gd = new GenericDialog("ABCD1 (scan L)");
        gd.addMessage("M = P(L)·M(R)·P(L)");
        gd.addNumericField("波长 (nm)", 2060, 0);
        gd.addNumericField("反射镜曲率半径 R (mm)", 500, 1);
        gd.addNumericField("L 扫描下限 (mm)", 150, 1);
        gd.addNumericField("L 扫描上限 (mm)", 350, 1);
        gd.addNumericField("采样点数", 400, 0);
        gd.showDialog();
        if (gd.wasCanceled()) {
            return;
        }
        lambdaMm = gd.getNextNumber() * 1e-6;
        double R = gd.getNextNumber();
        double lo = gd.getNextNumber();
        double hi = gd.getNextNumber();
        int n = (int) gd.getNextNumber();
        if (n < 2 || hi <= lo) {
            IJ.error("参数无效：需 hi>lo 且采样点数≥2");
            return;
        }
        double[] x = new double[n], w = new double[n];
        for (int i = 0; i < n; i++) {
            double L = lo + (hi - lo) * i / (n - 1);
            GausCore.Mat m = GausCore.Mat.propagation(L).mul(GausCore.Mat.mirror(R))
                    .mul(GausCore.Mat.propagation(L));
            x[i] = L;
            w[i] = GausCore.waistFromAbcd(m, lambdaMm);
        }
        String stable = GausUtil.stableIntervals(x, w);
        Plot plot = new Plot("ABCD1 (scan L)", "L / mm", "w0 / mm");
        PlotUtil.applyStyle(plot, "L / mm", "w0 / mm", 1, 4,
                Plot.LEGEND_UPPER_RIGHT, true, false, true);
        GausUtil.addSplitLine(plot, x, w, Color.BLUE, "waist w0");
        plot.setNorthAnnotation("R=" + GausUtil.fmt(R) + " mm, λ=" + GausUtil.fmt(lambdaMm * 1e6) + " nm");
        plot.setAnnotation("stable: " + stable);
        plot.showPlot();
        IJ.log("=== ABCD1 (scan L) ===\nR=" + GausUtil.fmt(R) + " mm\nstable L: " + stable + " mm\n");
    }
}
