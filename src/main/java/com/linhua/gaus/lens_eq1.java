package com.linhua.gaus;

import ij.IJ;
import ij.gui.GenericDialog;
import ij.plugin.PlugIn;
import java.util.function.BiFunction;

/** 对应 lens_eq1.py：令 m1·M(R1)·m2·M(R2)·m2·M(R1)·m1 等于单个反射镜 M(R)，求 Z1,Z2。 */
public class lens_eq1 implements PlugIn {

    @Override
    public void run(String arg) {
        GenericDialog gd = new GenericDialog("Equivalence1 (lens_eq1)");
        gd.addMessage("M = m1·M(R1)·m2·M(R2)·m2·M(R1)·m1");
        gd.addNumericField("目标反射镜 R (mm)", 750, 1);
        gd.addNumericField("反射镜 R1 (mm)", 800, 1);
        gd.addNumericField("反射镜 R2 (mm)", 500, 1);
        gd.addNumericField("Z1 初值 (mm)", 100, 1);
        gd.addNumericField("Z2 初值 (mm)", 100, 1);
        gd.showDialog();
        if (gd.wasCanceled()) {
            return;
        }
        double R = gd.getNextNumber();
        double R1 = gd.getNextNumber();
        double R2 = gd.getNextNumber();
        double z1 = gd.getNextNumber();
        double z2 = gd.getNextNumber();
        GausCore.Mat target = GausCore.Mat.mirror(R);
        BiFunction<Double, Double, double[]> build = (a, b) -> {
            GausCore.Mat m = GausCore.Mat.propagation(a).mul(GausCore.Mat.mirror(R1))
                    .mul(GausCore.Mat.propagation(b)).mul(GausCore.Mat.mirror(R2))
                    .mul(GausCore.Mat.propagation(b)).mul(GausCore.Mat.mirror(R1))
                    .mul(GausCore.Mat.propagation(a));
            return new double[]{m.a - target.a, m.b - target.b};
        };
        lens_eq.solveAndReport("Equivalence1 (lens_eq1)", R, R1, R2, z1, z2, build, target);
    }
}
