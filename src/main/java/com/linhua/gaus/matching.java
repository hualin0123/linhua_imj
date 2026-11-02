package com.linhua.gaus;

import ij.IJ;
import ij.gui.GenericDialog;
import ij.plugin.PlugIn;

/** 对应 matching.py：给定两束腰斑 w1、w2 与透镜焦距 F，求四组模匹配距离 L1/L2/La/Lb。 */
public class matching implements PlugIn {
    private double lambdaMm = GausCore.DEFAULT_LAMBDA_MM;

    @Override
    public void run(String arg) {
        GenericDialog gd = new GenericDialog("Mode Matching");
        gd.addMessage("M = P(L1)·lens(F)·P(L2)（模匹配四解：L1,L2,La,Lb）");
        gd.addNumericField("波长 (nm)", 2060, 0);
        gd.addNumericField("束腰 w1 (mm)", 0.007, 5);
        gd.addNumericField("束腰 w2 (mm)", 0.5, 4);
        gd.addNumericField("透镜焦距 F (mm)", 12.5, 3);
        gd.showDialog();
        if (gd.wasCanceled()) {
            return;
        }
        lambdaMm = gd.getNextNumber() * 1e-6;
        double w1 = gd.getNextNumber();
        double w2 = gd.getNextNumber();
        double F = gd.getNextNumber();
        double[] r = GausCore.modeMatching(w1, w2, F, lambdaMm);
        IJ.log("=== Mode Matching ===\nw1=" + GausUtil.fmt(w1) + ", w2=" + GausUtil.fmt(w2)
                + " mm, F=" + GausUtil.fmt(F) + " mm, λ=" + GausUtil.fmt(lambdaMm * 1e6) + " nm\n"
                + "L1 = " + GausUtil.fmt(r[0]) + " mm\n"
                + "L2 = " + GausUtil.fmt(r[1]) + " mm\n"
                + "La = " + GausUtil.fmt(r[2]) + " mm\n"
                + "Lb = " + GausUtil.fmt(r[3]) + " mm\n");
        IJ.showMessage("模匹配结果",
                "L1 = " + GausUtil.fmt(r[0]) + " mm\nL2 = " + GausUtil.fmt(r[1])
                        + " mm\nLa = " + GausUtil.fmt(r[2]) + " mm\nLb = " + GausUtil.fmt(r[3]) + " mm");
    }
}
