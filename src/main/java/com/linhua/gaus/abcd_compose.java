package com.linhua.gaus;

import com.linhua.plot.Plot;
import com.linhua.plot.PlotUtil;
import ij.IJ;
import ij.gui.GenericDialog;
import ij.plugin.PlugIn;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自由组合 ABCD 计算器：用户在文本框里按顺序书写光学元件序列（单位 mm），
 * 插件拼出总矩阵；给定种子腰斑 w0 时，画出沿轴光斑 w(z)。
 *
 * <p>写法示例：<pre>P(250) lens(100) M(500) P(250) lens(100)</pre>
 * <ul>
 *   <li>{@code P(L)}      自由空间传播 L mm；</li>
 *   <li>{@code lens(f)}   薄透镜，焦距 f mm；</li>
 *   <li>{@code M(R)}      反射镜（曲率半径 R mm，R&lt;0 为凸面）。</li>
 * </ul>
 * 元件按“光走过的顺序”从左到右书写；种子腰斑 w0 取在序列最左端（输入面）。
 * 总矩阵按遍历顺序相乘：M = Eₙ·…·E₂·E₁。
 */
public class abcd_compose implements PlugIn {
    private static final Pattern TOK = Pattern.compile(
            "([A-Za-z]+)\\s*\\(\\s*(-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)\\s*\\)");

    private double lambdaMm = GausCore.DEFAULT_LAMBDA_MM;

    @Override
    public void run(String arg) {
        GenericDialog gd = new GenericDialog("ABCD 自由组合");
        gd.addMessage("按顺序书写元件序列（单位 mm），光斑 w0 取在最左端：");
        gd.addMessage("P(L) 自由空间   lens(f) 薄透镜   M(R) 反射镜(R<0 为凸面)");
        gd.addStringField("元件序列", "P(250) lens(100) M(500) P(250) lens(100)");
        gd.addNumericField("波长 (nm)", 2060, 0);
        gd.addNumericField("种子腰斑 w0 (mm, 0=仅显示矩阵)", 0.3, 3);
        gd.addNumericField("绘制 w(z) 范围 Zmax (mm, 0=不画)", 1000, 1);
        gd.addNumericField("采样点数", 400, 0);
        gd.showDialog();
        if (gd.wasCanceled()) {
            return;
        }
        String seq = gd.getNextString();
        lambdaMm = gd.getNextNumber() * 1e-6;
        double w0 = gd.getNextNumber();
        double zmax = gd.getNextNumber();
        int n = (int) gd.getNextNumber();

        List<GausCore.Mat> elems = parse(seq);
        if (elems.isEmpty()) {
            IJ.error("未能解析到任何元件，请检查序列写法。");
            return;
        }
        // 按遍历顺序相乘：M = E.mul(M)
        GausCore.Mat M = new GausCore.Mat(1, 0, 0, 1);
        for (GausCore.Mat e : elems) {
            M = e.mul(M);
        }
        double det = M.a * M.d - M.b * M.c;
        double stab = M.stability();

        StringBuilder summary = new StringBuilder();
        summary.append("M = ").append(seq.trim()).append("\n")
                .append(String.format("  A = %14.5g    B = %14.5g\n", M.a, M.b))
                .append(String.format("  C = %14.5g    D = %14.5g\n", M.c, M.d))
                .append(String.format("det(M) = %14.5g    (A+D)/2 = %14.5g\n", det, stab));
        if (Math.abs(det - 1) > 1e-9) {
            summary.append("注意：det(M)≠1，可能含非无损元件或解析错误。\n");
        }

        if (w0 <= 0) {
            IJ.log("=== ABCD 自由组合（仅矩阵）===\n" + summary);
            IJ.showMessage("ABCD 自由组合", summary.toString());
            return;
        }

        // 输入面为束腰，q0 = i·π·w0²/λ
        GausCore.Cx q0 = GausCore.qFromWaist(w0, Double.POSITIVE_INFINITY, lambdaMm);
        GausCore.Cx den = q0.mul(GausCore.Cx.of(M.c)).add(GausCore.Cx.of(M.d));
        GausCore.Cx qOut;
        if (Math.abs(den.re) < 1e-9 && Math.abs(den.im) < 1e-9) {
            qOut = null; // 输出为平行光（q→∞）
        } else {
            qOut = GausCore.propagateQ(M, q0);
        }

        if (qOut == null) {
            summary.append("输出为平行光（q→∞），无法定义有限腰斑。\n");
            IJ.log("=== ABCD 自由组合 ===\n" + summary);
            IJ.showMessage("ABCD 自由组合", summary.toString());
            return;
        }

        double wOut = GausCore.waistFromQ(qOut, lambdaMm);
        double relWaist = -qOut.re; // 束腰相对输出面的位置（mm，负=在输出面前游）
        summary.append(String.format("输入 w0 = %.4g mm\n", w0));
        summary.append(String.format("输出 w  = %.4g mm    束腰相对输出面位置 = %.4g mm\n", wOut, relWaist));

        if (zmax <= 0 || n < 2) {
            IJ.log("=== ABCD 自由组合 ===\n" + summary);
            IJ.showMessage("ABCD 自由组合", summary.toString());
            return;
        }

        // 沿输出面之后继续传播，画 w(z)
        double[] x = new double[n], w = new double[n];
        for (int i = 0; i < n; i++) {
            double z = zmax * i / (n - 1);
            GausCore.Cx qz = GausCore.propagateQ(GausCore.Mat.propagation(z), qOut);
            x[i] = z;
            w[i] = GausCore.waistFromQ(qz, lambdaMm);
        }
        Plot plot = new Plot("ABCD 自由组合", "z（输出面之后）/ mm", "w(z) / mm");
        PlotUtil.applyStyle(plot, "z / mm", "w(z) / mm", 1, 4,
                Plot.LEGEND_UPPER_RIGHT, true, false, true);
        GausUtil.addSplitLine(plot, x, w, Color.BLUE, "w(z)");
        plot.setNorthAnnotation("M = " + seq.trim());
        plot.setAnnotation(summary.toString());
        plot.showPlot();
        IJ.log("=== ABCD 自由组合 ===\n" + summary);
    }

    /** 解析元件序列，返回按书写顺序排列的矩阵列表（忽略未知元件）。 */
    private List<GausCore.Mat> parse(String s) {
        List<GausCore.Mat> out = new ArrayList<>();
        Matcher m = TOK.matcher(s);
        while (m.find()) {
            String name = m.group(1).toLowerCase();
            double v = Double.parseDouble(m.group(2));
            switch (name) {
                case "p":
                case "prop":
                case "propagation":
                    out.add(GausCore.Mat.propagation(v));
                    break;
                case "lens":
                case "l":
                    out.add(GausCore.Mat.lens(v));
                    break;
                case "m":
                case "mirror":
                    out.add(GausCore.Mat.mirror(v));
                    break;
                default:
                    IJ.log("忽略未知元件: " + name);
            }
        }
        return out;
    }
}
