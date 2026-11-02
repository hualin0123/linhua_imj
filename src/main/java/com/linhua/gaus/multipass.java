package com.linhua.gaus;

import com.linhua.plot.Plot;
import com.linhua.plot.PlotUtil;
import ij.IJ;
import ij.gui.GenericDialog;
import ij.plugin.PlugIn;
import java.awt.Color;

/** 对应 multipass.py：单程 ABCD_1 的 N 次幂，逐程解 q 参数得腰斑与曲率半径。 */
public class multipass implements PlugIn {
    private double lambdaMm = GausCore.DEFAULT_LAMBDA_MM;

    @Override
    public void run(String arg) {
        GenericDialog gd = new GenericDialog("Multipass");
        gd.addMessage("M = P(L1)·f·P(L2)·P(L2)·f·P(L1)（单程 ABCD_1）");
        gd.addNumericField("波长 (nm)", 2060, 0);
        gd.addNumericField("入射束腰 w1 (mm)", 0.2, 4);
        gd.addNumericField("长度 L1 (mm)", 120, 1);
        gd.addNumericField("透镜焦距 f (mm)", 100, 1);
        gd.addNumericField("长度 L2 (mm)", 120, 1);
        gd.addNumericField("程数 N", 10, 0);
        gd.showDialog();
        if (gd.wasCanceled()) {
            return;
        }
        lambdaMm = gd.getNextNumber() * 1e-6;
        double w1 = gd.getNextNumber();
        double L1 = gd.getNextNumber();
        double f = gd.getNextNumber();
        double L2 = gd.getNextNumber();
        int passes = (int) gd.getNextNumber();
        if (passes < 1) {
            IJ.error("程数需 ≥ 1");
            return;
        }
        GausCore.Mat abcd1 = GausCore.Mat.propagation(L1).mul(GausCore.Mat.lens(f))
                .mul(GausCore.Mat.propagation(L2)).mul(GausCore.Mat.propagation(L2))
                .mul(GausCore.Mat.lens(f)).mul(GausCore.Mat.propagation(L1));
        GausCore.Cx q1 = GausCore.qFromWaist(w1, Double.POSITIVE_INFINITY, lambdaMm);
        double[] x = new double[passes], yw = new double[passes], yR = new double[passes];
        for (int k = 0; k < passes; k++) {
            GausCore.Mat mn = abcd1.pow(k + 1);
            GausCore.Cx q2 = GausCore.propagateQ(mn, q1);
            x[k] = k + 1;
            yw[k] = GausCore.waistFromQ(q2, lambdaMm);
            double invR = GausCore.curvatureInv(q2);
            yR[k] = invR == 0 ? Double.POSITIVE_INFINITY : 1.0 / invR;
        }
        Plot plot = new Plot("Multipass", "pass number N", "w / R (mm)");
        PlotUtil.applyStyle(plot, "pass number N", "w / R (mm)", 0, 3,
                Plot.LEGEND_UPPER_RIGHT, true, false, true);
        GausUtil.addSplitLine(plot, x, yw, Color.RED, "waist w");
        GausUtil.addSplitLine(plot, x, yR, Color.BLUE, "R (radius)");
        plot.setNorthAnnotation("w1=" + GausUtil.fmt(w1) + ", L1=" + GausUtil.fmt(L1) + ", f=" + GausUtil.fmt(f)
                + ", L2=" + GausUtil.fmt(L2) + " mm, λ=" + GausUtil.fmt(lambdaMm * 1e6) + " nm");
        plot.showPlot();
        StringBuilder sb = new StringBuilder("=== Multipass ===\n");
        for (int k = 0; k < passes; k++) {
            sb.append("pass ").append(k + 1).append(": w=").append(GausUtil.fmt(yw[k]))
                    .append(" mm, R=").append(Double.isInfinite(yR[k]) ? "∞" : GausUtil.fmt(yR[k])).append(" mm\n");
        }
        IJ.log(sb.toString());
    }
}
