package com.linhua.gaus;

import ij.IJ;
import ij.gui.GenericDialog;
import ij.plugin.PlugIn;
import java.util.function.BiFunction;

/** 对应 lens_eq.py：令 m1·M(R1)·m2·m2·M(R1)·m1 等于单个反射镜 M(R)，求 Z1,Z2。 */
public class lens_eq implements PlugIn {

    @Override
    public void run(String arg) {
        GenericDialog gd = new GenericDialog("Equivalence (lens_eq)");
        gd.addMessage("M = m1·M(R1)·m2·m2·M(R1)·m1");
        gd.addNumericField("目标反射镜 R (mm)", -200, 1);
        gd.addNumericField("反射镜 R1 (mm)", 800, 1);
        gd.addNumericField("Z1 初值 (mm)", 100, 1);
        gd.addNumericField("Z2 初值 (mm)", 100, 1);
        gd.showDialog();
        if (gd.wasCanceled()) {
            return;
        }
        double R = gd.getNextNumber();
        double R1 = gd.getNextNumber();
        double z1 = gd.getNextNumber();
        double z2 = gd.getNextNumber();
        GausCore.Mat target = GausCore.Mat.mirror(R);
        BiFunction<Double, Double, double[]> build = (a, b) -> {
            GausCore.Mat m = GausCore.Mat.propagation(a).mul(GausCore.Mat.mirror(R1))
                    .mul(GausCore.Mat.propagation(b)).mul(GausCore.Mat.propagation(b))
                    .mul(GausCore.Mat.mirror(R1)).mul(GausCore.Mat.propagation(a));
            return new double[]{m.a - target.a, m.b - target.b};
        };
        solveAndReport("Equivalence (lens_eq)", R, R1, Double.NaN, z1, z2, build, target);
    }

    /** 解并输出（R2 仅用于日志，可传 NaN）。 */
    static void solveAndReport(String title, double R, double R1, double R2,
                               double z1, double z2,
                               BiFunction<Double, Double, double[]> build,
                               GausCore.Mat target) {
        double[] sol = GausUtil.newton2D(z1, z2, build);
        if (sol == null) {
            IJ.error("牛顿法未收敛，请尝试更换初值。");
            return;
        }
        GausCore.Mat m = GausCore.Mat.propagation(sol[0]).mul(GausCore.Mat.mirror(R1))
                .mul(GausCore.Mat.propagation(sol[1])).mul(GausCore.Mat.propagation(sol[1]))
                .mul(GausCore.Mat.mirror(R1)).mul(GausCore.Mat.propagation(sol[0]));
        StringBuilder sb = new StringBuilder("=== " + title + " ===\n")
                .append("R=").append(GausUtil.fmt(R)).append(" mm, R1=").append(GausUtil.fmt(R1)).append(" mm");
        if (!Double.isNaN(R2)) {
            sb.append(", R2=").append(GausUtil.fmt(R2)).append(" mm");
        }
        sb.append("\nZ1 = ").append(GausUtil.fmt(sol[0])).append(" mm\n")
                .append("Z2 = ").append(GausUtil.fmt(sol[1])).append(" mm\n")
                .append("M2 = [").append(GausUtil.fmt(m.a)).append(", ").append(GausUtil.fmt(m.b))
                .append("; ").append(GausUtil.fmt(m.c)).append(", ").append(GausUtil.fmt(m.d)).append("]\n")
                .append("M1 = [").append(GausUtil.fmt(target.a)).append(", ").append(GausUtil.fmt(target.b))
                .append("; ").append(GausUtil.fmt(target.c)).append(", ").append(GausUtil.fmt(target.d)).append("]\n");
        IJ.log(sb.toString());
        IJ.showMessage(title, "Z1 = " + GausUtil.fmt(sol[0]) + " mm\nZ2 = " + GausUtil.fmt(sol[1]) + " mm");
    }
}
