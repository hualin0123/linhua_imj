package com.linhua.gaus;

import com.linhua.plot.Plot;
import com.linhua.plot.PlotUtil;
import ij.IJ;
import ij.gui.GenericDialog;
import ij.plugin.PlugIn;
import java.awt.Color;

/** 对应 abcdx.py：对多个反射镜半径 R 分别画 w0(f)，矩阵 M(R)·f·P(L)·P(L)·f。 */
public class abcdx implements PlugIn {
    private double lambdaMm = GausCore.DEFAULT_LAMBDA_MM;

    @Override
    public void run(String arg) {
        GenericDialog gd = new GenericDialog("ABCDx (multi-R)");
        gd.addMessage("M = M(R)·f·P(L)·P(L)·f");
        gd.addNumericField("波长 (nm)", 2060, 0);
        gd.addStringField("反射镜半径列表 R (逗号分隔, mm)", "400, 300, 200");
        gd.addNumericField("长度 L (mm)", 100, 1);
        gd.addNumericField("f 扫描下限 (mm)", -2000, 1);
        gd.addNumericField("f 扫描上限 (mm)", -100, 1);
        gd.addNumericField("采样点数", 400, 0);
        gd.showDialog();
        if (gd.wasCanceled()) {
            return;
        }
        lambdaMm = gd.getNextNumber() * 1e-6;
        String rStr = gd.getNextString();
        double L = gd.getNextNumber();
        double lo = gd.getNextNumber();
        double hi = gd.getNextNumber();
        int n = (int) gd.getNextNumber();
        if (n < 2 || hi <= lo) {
            IJ.error("参数无效：需 hi>lo 且采样点数≥2");
            return;
        }
        double[] Rs = GausUtil.parseList(rStr);
        if (Rs.length == 0) {
            IJ.error("反射镜半径列表解析为空");
            return;
        }
        GausCore.Mat mL = GausCore.Mat.propagation(L);
        Color[] cols = PlotUtil.assignColors(Rs.length);
        Plot plot = new Plot("ABCDx (multi-R)", "f / mm", "w0 / mm");
        PlotUtil.applyStyle(plot, "f / mm", "w0 / mm", 0, 4,
                Plot.LEGEND_UPPER_RIGHT, true, false, true);
        StringBuilder sb = new StringBuilder("=== ABCDx (multi-R) ===\nL=" + GausUtil.fmt(L)
                + " mm, λ=" + GausUtil.fmt(lambdaMm * 1e6) + " nm\n");
        for (int k = 0; k < Rs.length; k++) {
            double R = Rs[k];
            GausCore.Mat mR = GausCore.Mat.mirror(R);
            double[] x = new double[n], w = new double[n];
            for (int i = 0; i < n; i++) {
                double f = lo + (hi - lo) * i / (n - 1);
                GausCore.Mat m = mR.mul(GausCore.Mat.lens(f)).mul(mL).mul(mL)
                        .mul(GausCore.Mat.lens(f));
                x[i] = f;
                w[i] = GausCore.waistFromAbcd(m, lambdaMm);
            }
            GausUtil.addSplitLine(plot, x, w, cols[k], "R=" + GausUtil.fmt(R));
            sb.append("R=").append(GausUtil.fmt(R)).append(" mm  stable f: ")
                    .append(GausUtil.stableIntervals(x, w)).append(" mm\n");
        }
        plot.setNorthAnnotation("L=" + GausUtil.fmt(L) + " mm, λ=" + GausUtil.fmt(lambdaMm * 1e6) + " nm");
        plot.showPlot();
        IJ.log(sb.toString());
    }
}
