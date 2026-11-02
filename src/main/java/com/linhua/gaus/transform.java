package com.linhua.gaus;

import com.linhua.plot.Plot;
import com.linhua.plot.PlotUtil;
import ij.IJ;
import ij.gui.GenericDialog;
import ij.plugin.PlugIn;
import java.awt.Color;

/** 对应 transform.py：束腰 w0 经焦距 F 透镜，扫描可变距离 L，画 L1 与 w1。 */
public class transform implements PlugIn {
    private double lambdaMm = GausCore.DEFAULT_LAMBDA_MM;

    @Override
    public void run(String arg) {
        GenericDialog gd = new GenericDialog("Lens Transform");
        gd.addMessage("M = 薄透镜 M(F)=[1,0;-1/F,1]（L1,w1 随可变距离 L 变化）");
        gd.addNumericField("波长 (nm)", 2060, 0);
        gd.addNumericField("入射束腰 w0 (mm)", 0.00728, 5);
        gd.addNumericField("透镜焦距 F (mm)", 12.5, 3);
        gd.addNumericField("L 扫描下限 (mm)", 12.501, 4);
        gd.addNumericField("L 扫描上限 (mm)", 13.0, 4);
        gd.addNumericField("采样点数", 200, 0);
        gd.showDialog();
        if (gd.wasCanceled()) {
            return;
        }
        lambdaMm = gd.getNextNumber() * 1e-6;
        double w0 = gd.getNextNumber();
        double F = gd.getNextNumber();
        double lo = gd.getNextNumber();
        double hi = gd.getNextNumber();
        int n = (int) gd.getNextNumber();
        if (n < 2 || hi <= lo) {
            IJ.error("参数无效：需 hi>lo 且采样点数≥2");
            return;
        }
        double[] x = new double[n], yL1 = new double[n], yw1 = new double[n];
        for (int i = 0; i < n; i++) {
            double L = lo + (hi - lo) * i / (n - 1);
            double[] r = GausCore.lensTransform(w0, F, L, lambdaMm);
            x[i] = L;
            yL1[i] = r[0];
            yw1[i] = r[1];
        }
        Plot plot = new Plot("Lens Transform", "L / mm", "L1 / w1 (mm)");
        PlotUtil.applyStyle(plot, "L / mm", "L1 / w1 (mm)", 4, 5,
                Plot.LEGEND_UPPER_RIGHT, true, false, true);
        GausUtil.addSplitLine(plot, x, yL1, Color.RED, "L1");
        GausUtil.addSplitLine(plot, x, yw1, Color.BLUE, "w1");
        plot.setNorthAnnotation("w0=" + GausUtil.fmt(w0) + ", F=" + GausUtil.fmt(F)
                + " mm, λ=" + GausUtil.fmt(lambdaMm * 1e6) + " nm");
        plot.showPlot();
        IJ.log("=== Lens Transform ===\nw0=" + GausUtil.fmt(w0) + ", F=" + GausUtil.fmt(F)
                + " mm, λ=" + GausUtil.fmt(lambdaMm * 1e6) + " nm\n");
    }
}
