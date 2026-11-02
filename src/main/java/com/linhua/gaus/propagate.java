package com.linhua.gaus;

import ij.IJ;
import ij.gui.GenericDialog;
import ij.plugin.PlugIn;

/** 对应 propagate.py：自由空间传播单点，输出瑞利范围 f 与传播后腰斑 w1。 */
public class propagate implements PlugIn {
    private double lambdaMm = GausCore.DEFAULT_LAMBDA_MM;

    @Override
    public void run(String arg) {
        GenericDialog gd = new GenericDialog("Propagation");
        gd.addMessage("M = 自由空间传播 P(L)=[1,L;0,1]");
        gd.addNumericField("波长 (nm)", 2060, 0);
        gd.addNumericField("入射束腰 w0 (mm)", 0.1, 4);
        gd.addNumericField("传播距离 L (mm)", 200, 1);
        gd.showDialog();
        if (gd.wasCanceled()) {
            return;
        }
        lambdaMm = gd.getNextNumber() * 1e-6;
        double w0 = gd.getNextNumber();
        double L = gd.getNextNumber();
        double f = GausCore.rayleigh(w0, lambdaMm);
        double w1 = GausCore.propagateW(w0, L, lambdaMm);
        IJ.log("=== Propagation ===\nw0=" + GausUtil.fmt(w0) + ", λ=" + GausUtil.fmt(lambdaMm * 1e6)
                + " nm\nf (Rayleigh)=" + GausUtil.fmt(f) + " mm\nw1(L=" + GausUtil.fmt(L)
                + ")=" + GausUtil.fmt(w1) + " mm\n");
        IJ.showMessage("Propagation", "f (Rayleigh) = " + GausUtil.fmt(f)
                + " mm\nw1 = " + GausUtil.fmt(w1) + " mm");
    }
}
