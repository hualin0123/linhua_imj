package com.linhua.plot;

import ij.IJ;
import ij.gui.GenericDialog;
import ij.plugin.PlugIn;
import java.awt.Color;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 多文件 Beam Pointing 对比：弹多选对话框选多个 CSV，
 * 按相对时间叠加各次测量的合成偏移轨迹与 X–Y 位置云，并在 Log 输出跨 run 的 RMS/指向/位置统计。
 * 可选「对齐各 run 中心」（减去各自质心）以便纯粹比较指向抖动；
 * X–Y 对比散点可选「空心标记」。
 * 数据解析共用 {@link BeamPointingCore}。
 */
public class BeamPointingCompare_ implements PlugIn {

    /** 对比散点用不同形状区分各 run（颜色叠加时仍可辨形）。 */
    private static final int[] SHAPES = {
        Plot.MARKER_CIRCLE, Plot.MARKER_TRIANGLE, Plot.MARKER_SQUARE, Plot.MARKER_DIAMOND
    };

    /** 给颜色加透明度（0–255），便于多 run 叠加时混色而非遮盖。 */
    private static Color withAlpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
    }

    @Override
    public void run(String arg) {
        File[] filesArr = PlotUtil.chooseFiles("选择 Beam Pointing CSV（可多选）", "csv");
        if (filesArr.length == 0) {
            return;
        }
        List<File> files = Arrays.asList(filesArr);

        GenericDialog gd = new GenericDialog("对比选项");
        gd.addCheckbox("对齐各 run 中心（减去各自质心，便于比较抖动）", false);
        gd.addCheckbox("X–Y 散点用空心标记", false);
        gd.showDialog();
        if (gd.wasCanceled()) {
            return;
        }
        boolean align = gd.getNextBoolean();
        boolean hollow = gd.getNextBoolean();

        try {
            compare(files, align, hollow);
        } catch (Exception e) {
            IJ.error("解析失败", e.getMessage());
            e.printStackTrace();
        }
    }

    private void compare(List<File> files, boolean align, boolean hollow) throws Exception {
        Color[] colors = PlotUtil.assignColors(files.size());
        List<BeamPointingCore.Data> datas = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (File f : files) {
            try {
                datas.add(BeamPointingCore.load(f.getAbsolutePath()));
                String nm = f.getName();
                int d = nm.lastIndexOf('.');
                names.add(d > 0 ? nm.substring(0, d) : nm);
            } catch (Exception e) {
                IJ.log("跳过无法解析的文件: " + f.getName() + " - " + e.getMessage());
            }
        }
        if (datas.isEmpty()) {
            IJ.error("没有可对比的有效数据。");
            return;
        }
        int n = datas.size();

        // 预计算每个 run 的质心（中心对齐用）与基于质心的 RMS（正确稳定性指标）
        double[][] xb = new double[n][], yb = new double[n][], rb = new double[n][];
        double[] xmean = new double[n], ymean = new double[n], rmean = new double[n], crms = new double[n];
        for (int i = 0; i < n; i++) {
            List<BeamPointingCore.Sample> s = datas.get(i).samples;
            int m = s.size();
            double[] x = new double[m], y = new double[m], r = new double[m];
            double sx = 0, sy = 0, sr = 0;
            for (int k = 0; k < m; k++) {
                x[k] = s.get(k).x;
                y[k] = s.get(k).y;
                r[k] = s.get(k).r;
                sx += x[k];
                sy += y[k];
                sr += r[k];
            }
            xmean[i] = sx / m;
            ymean[i] = sy / m;
            rmean[i] = sr / m;
            double ss = 0;
            for (int k = 0; k < m; k++) {
                double dx = x[k] - xmean[i], dy = y[k] - ymean[i];
                ss += dx * dx + dy * dy;
            }
            crms[i] = Math.sqrt(ss / m);
            if (align) {
                for (int k = 0; k < m; k++) {
                    x[k] -= xmean[i];
                    y[k] -= ymean[i];
                    r[k] -= rmean[i];
                }
            }
            xb[i] = x;
            yb[i] = y;
            rb[i] = r;
        }

        // ---- 时间轨迹：每 run 叠加合成偏移 (r) ----
        Plot trace = new Plot("Beam Pointing Compare - Time Trace", "Time / s", "Offset / um");
        PlotUtil.applyStyle(trace, "Time / s", "Offset / um", 0, 1, Plot.LEGEND_UPPER_LEFT, true, false, true);
        for (int i = 0; i < n; i++) {
            trace.addLine(toT(datas.get(i).samples), rb[i], colors[i], names.get(i));
        }
        trace.showPlot();

        // ---- X–Y 散点（半透明/空心，按 run 区分形状） ----
        Plot scatter = new Plot("Beam Pointing Compare - X-Y Scatter", "Offset X / um", "Offset Y / um");
        PlotUtil.applyStyle(scatter, "Offset X / um", "Offset Y / um", 1, 1, Plot.LEGEND_UPPER_LEFT, true, false, true);
        for (int i = 0; i < n; i++) {
            int shape = SHAPES[i % SHAPES.length];
            Color c = hollow ? colors[i] : withAlpha(colors[i], 110);
            float size = hollow ? 3.5f : 4f;
            float edge = hollow ? 0.8f : 1f;
            scatter.addScatter(xb[i], yb[i], c, names.get(i), shape, hollow, size, edge);
        }
        scatter.setOriginCross(true);
        scatter.showPlot();

        // ---- 跨 run 统计表 ----
        StringBuilder sb = new StringBuilder("=== Beam Pointing Compare ===\n");
        sb.append(String.format("%-22s %12s %14s %14s\n", "File", "RMS/um", "pointing/urad", "position/um"));
        for (int i = 0; i < n; i++) {
            BeamPointingCore.Data d = datas.get(i);
            double pointing = Double.NaN, position = Double.NaN;
            for (BeamPointingCore.Stats st : d.stats) {
                if (st.axis.isEmpty()) {
                    pointing = st.pointing;
                    position = st.position;
                }
            }
            double rms = align ? crms[i] : d.rms;
            sb.append(String.format("%-22s %12.3f %14s %14s\n",
                    names.get(i), rms,
                    Double.isNaN(pointing) ? "-" : String.format("%.3f", pointing),
                    Double.isNaN(position) ? "-" : String.format("%.3f", position)));
        }
        IJ.log(sb.toString());
    }

    private static double[] toT(List<BeamPointingCore.Sample> s) {
        double[] t = new double[s.size()];
        for (int k = 0; k < s.size(); k++) {
            t[k] = s.get(k).t;
        }
        return t;
    }
}
