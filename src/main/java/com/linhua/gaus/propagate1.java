package com.linhua.gaus;

import com.linhua.plot.Plot;
import com.linhua.plot.PlotUtil;
import ij.IJ;
import ij.gui.GenericDialog;
import ij.plugin.PlugIn;
import java.awt.Color;

/** 对应 propagate1.py：自由空间传播，扫描距离 L 画腰斑 w(L)。 */
public class propagate1 implements PlugIn {
    private double lambdaMm = GausCore.DEFAULT_LAMBDA_MM;

    @Override
    public void run(String arg) {
        GenericDialog gd = new GenericDialog("Propagation (sweep)");
        gd.addMessage("M = 自由空间传播 P(L)=[1,L;0,1]");
        gd.addNumericField("波长 (nm)", 2060, 0);
        gd.addNumericField("入射束腰 w0 (mm)", 0.3, 4);
        gd.addNumericField("L 扫描下限 (mm)", 0, 1);
        gd.addNumericField("L 扫描上限 (mm)", 1000, 1);
        gd.addNumericField("采样点数", 300, 0);
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
            double L = lo + (hi - lo) * i / (n - 1);
            x[i] = L;
            y[i] = GausCore.propagateW(w0, L, lambdaMm);
        }
        Plot plot = new Plot("Propagation", "L / mm", "w / mm");
        PlotUtil.applyStyle(plot, "L / mm", "w / mm", 0, 4,
                Plot.LEGEND_UPPER_LEFT, true, false, true);
        GausUtil.addSplitLine(plot, x, y, Color.BLUE, "w(L)");
        plot.setNorthAnnotation("w0=" + GausUtil.fmt(w0) + " mm, λ=" + GausUtil.fmt(lambdaMm * 1e6) + " nm");
        plot.showPlot();
        IJ.log("=== Propagation (sweep) ===\nw0=" + GausUtil.fmt(w0) + " mm, λ=" + GausUtil.fmt(lambdaMm * 1e6)
                + " nm\nf (Rayleigh)=" + GausUtil.fmt(GausCore.rayleigh(w0, lambdaMm)) + " mm\n");
    }
}
