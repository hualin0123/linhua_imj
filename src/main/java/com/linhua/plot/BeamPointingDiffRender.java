package com.linhua.plot;

import ij.IJ;
import ij.text.TextWindow;
import java.awt.Color;
import java.awt.Dimension;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.JTextField;
import javax.swing.SwingConstants;

/**
 * Beam Pointing Differential（透射器件指向稳定性差分）的共享渲染 + 计算。
 *
 * <p>原先 {@code BeamPointingDiff_} 的「差分计算 + 渲染」全部内联在插件里。本类把
 * <b>计算</b>抽成纯静态方法 {@link #compute}（时间对齐裁剪 → 重采样到公共网格 → 逐点差分，
 * 缺口置 NaN），把<b>渲染</b>抽成可换数据的实例（4 图 + 文本 Log + 传播距离框 + 导出 PNG）。
 * {@code BeamPointingDiff_}（整段数据）与 {@code BeamPointingPhaseDiff_}（分阶段）共用，
 * 避免重复且保证视觉/口径一致。</p>
 *
 * <p>计算部分不含 Swing，可被单测直接断言（见 BeamPointingDiffRenderTest）。</p>
 */
public class BeamPointingDiffRender {

    // ---- 计算相关的常量/工具（从 BeamPointingDiff_ 平移而来，纯静态） ----
    private static final double ALIGN_TOL = 1e-3;   // 时长差小于该值(s)视为一致，不裁剪
    private static final double GAP_FACTOR = 3.0;   // 相邻采样间隔超 名义步长×该值 即判定为缺失段(缺口)

    /** 一次差分计算的结果（纯数据，无 UI）。 */
    public static class DiffResult {
        public final double[] tg, Dx, Dy, D;                       // 公共网格/差分分量/合成(μm)
        public final List<BeamPointingCore.Sample> validSamples;   // 两臂均有真实数据的点(供统计/散点)
        public final List<BeamPointingCore.Sample> diffSamples;    // 全网格点(缺口为 NaN，供绘图断线)
        public final double corrX, corrY;                         // 两臂 Pearson 相关系数
        public final double rmsMeas;                               // 测量臂 RMS(供共模抑制显示)
        public final String alignNote;                             // 对齐/相关性/缺口诊断文本
        public final String refName, measName;

        public DiffResult(double[] tg, double[] Dx, double[] Dy, double[] D,
                          List<BeamPointingCore.Sample> validSamples,
                          List<BeamPointingCore.Sample> diffSamples,
                          double corrX, double corrY, String alignNote,
                          double rmsMeas, String refName, String measName) {
            this.tg = tg;
            this.Dx = Dx;
            this.Dy = Dy;
            this.D = D;
            this.validSamples = validSamples;
            this.diffSamples = diffSamples;
            this.corrX = corrX;
            this.corrY = corrY;
            this.rmsMeas = rmsMeas;
            this.alignNote = alignNote;
            this.refName = refName;
            this.measName = measName;
        }
    }

    /**
     * 差分核心计算（与旧 {@code BeamPointingDiff_.process} 计算部分等价，去掉渲染）：
     * 时间对齐裁剪 → 重采样到公共网格 → D = meas − ref（k=1，缺口处整体置 NaN）。
     * 纯计算、无 Swing；任一臂为空或无重叠时抛 {@link Exception}。
     */
    public static DiffResult compute(BeamPointingCore.Data ref, BeamPointingCore.Data meas,
                                     String refName, String measName) throws Exception {
        double rt0 = ref.samples.get(0).t, rt1 = ref.samples.get(ref.samples.size() - 1).t;
        double mt0 = meas.samples.get(0).t, mt1 = meas.samples.get(meas.samples.size() - 1).t;
        double durRef = rt1 - rt0, durMeas = mt1 - mt0;
        double t0 = Math.max(rt0, mt0);
        double t1 = Math.min(rt1, mt1);
        StringBuilder alignNote = new StringBuilder();
        if (Math.abs(durRef - durMeas) > ALIGN_TOL) {
            alignNote.append(String.format(
                    "【时间对齐】两文件测试时长不一致，已自动将较长的文件裁剪到共同时间区间 [%.2f, %.2f] s（重叠 %.2f s）\n",
                    t0, t1, t1 - t0));
            if (durRef > durMeas) {
                int before = ref.samples.size();
                ref = trimToRange(ref, t0, t1);
                alignNote.append(String.format(
                        "  参考臂 %s: %.2f s (%d 点) → 裁剪为 %.2f s (%d 点)，丢弃 %.2f s / %d 点\n",
                        refName, durRef, before, t1 - t0, ref.samples.size(),
                        durRef - (t1 - t0), before - ref.samples.size()));
            } else {
                int before = meas.samples.size();
                meas = trimToRange(meas, t0, t1);
                alignNote.append(String.format(
                        "  测量臂 %s: %.2f s (%d 点) → 裁剪为 %.2f s (%d 点)，丢弃 %.2f s / %d 点\n",
                        measName, durMeas, before, t1 - t0, meas.samples.size(),
                        durMeas - (t1 - t0), before - meas.samples.size()));
            }
        }
        if (t1 <= t0) {
            throw new Exception("两文件时间不重叠，请确认所选 CSV 内容");
        }
        double[] rt = toArr(ref.samples, 0), rx = toArr(ref.samples, 1), ry = toArr(ref.samples, 2);
        double[] mt = toArr(meas.samples, 0), mx = toArr(meas.samples, 1), my = toArr(meas.samples, 2);

        double stepR = nominalStep(rt, GAP_FACTOR), stepM = nominalStep(mt, GAP_FACTOR);
        double dt = Math.min(stepR, stepM);
        int ng = (int) Math.floor((t1 - t0) / dt) + 1;
        double[] tg = new double[ng];
        for (int i = 0; i < ng; i++) {
            tg[i] = t0 + i * dt;
        }

        double gapR = GAP_FACTOR * stepR, gapM = GAP_FACTOR * stepM;
        double[] rgx = resample(rt, rx, tg, gapR), rgy = resample(rt, ry, tg, gapR);
        double[] mgx = resample(mt, mx, tg, gapM), mgy = resample(mt, my, tg, gapM);

        double corrX = corrValid(rgx, mgx);
        double corrY = corrValid(rgy, mgy);
        alignNote.append("【相关性诊断】两臂相关系数(参考臂 vs 测量臂)：\n");
        appendCorr(alignNote, "X", corrX);
        appendCorr(alignNote, "Y", corrY);

        double[] Dx = new double[ng], Dy = new double[ng], D = new double[ng];
        BeamPointingDiff_.differential(rgx, rgy, mgx, mgy, Dx, Dy, D);

        List<BeamPointingCore.Sample> diffSamples = new ArrayList<>();
        List<BeamPointingCore.Sample> validSamples = new ArrayList<>();
        for (int i = 0; i < ng; i++) {
            BeamPointingCore.Sample s = new BeamPointingCore.Sample();
            s.t = tg[i];
            s.x = Dx[i];
            s.y = Dy[i];
            s.r = D[i];
            diffSamples.add(s);
            if (!Double.isNaN(D[i])) {
                validSamples.add(s);
            }
        }

        int gapPtsR = countNaN(rgx), gapPtsM = countNaN(mgx);
        if (gapPtsR > 0 || gapPtsM > 0) {
            alignNote.append("【数据缺口】检测到文件中间存在缺失数据段，已自动跳过这些时刻(不参与插值/差分)：\n");
            if (gapPtsR > 0) {
                alignNote.append(String.format("  参考臂 %s: %d / %d 个网格点位于缺口，已排除\n", refName, gapPtsR, ng));
            }
            if (gapPtsM > 0) {
                alignNote.append(String.format("  测量臂 %s: %d / %d 个网格点位于缺口，已排除\n", measName, gapPtsM, ng));
            }
        }
        if (validSamples.isEmpty()) {
            throw new Exception("两文件在重叠时段内均无共同有效数据，无法计算差分。");
        }
        double rmsMeas = BeamPointingCore.centroidRmsUm(meas.samples);
        return new DiffResult(tg, Dx, Dy, D, validSamples, diffSamples,
                corrX, corrY, alignNote.toString(), rmsMeas, refName, measName);
    }

    // ===================== 实例：渲染 + 交互 + 导出 =====================

    private DiffResult result;
    private final Plot timeTrace, offX, offY, scatter;
    private final JTextField distField = new JTextField(4);
    private double distMm = 0;
    private File outDir;
    private String baseName = "BeamPointing";
    private static TextWindow diffLogWnd; // 差分统计输出窗口(标题=Beam Pointing Differential)，复用避免堆叠

    public BeamPointingDiffRender(DiffResult result) {
        this.result = result;

        timeTrace = new Plot("Beam Pointing (Differential) - Time Trace", "Time / s", "Offset / um");
        PlotUtil.applyStyle(timeTrace, "Time / s", "Offset / um", 0, 1, Plot.LEGEND_UPPER_LEFT, true, false, true);

        offX = new Plot("Beam Pointing (Differential) - Offset X Time Trace", "Time / s", "Offset X / um");
        PlotUtil.applyStyle(offX, "Time / s", "Offset X / um", 0, 1, Plot.LEGEND_UPPER_LEFT, true, false, true);

        offY = new Plot("Beam Pointing (Differential) - Offset Y Time Trace", "Time / s", "Offset Y / um");
        PlotUtil.applyStyle(offY, "Time / s", "Offset Y / um", 0, 1, Plot.LEGEND_UPPER_LEFT, true, false, true);

        scatter = new Plot("Beam Pointing (Differential) - X-Y Scatter", "Offset X / um", "Offset Y / um");
        PlotUtil.applyStyle(scatter, "Offset X / um", "Offset Y / um", 1, 1, Plot.LEGEND_UPPER_LEFT, true, false, true);
    }

    public void setExport(File outDir, String baseName) {
        this.outDir = outDir;
        this.baseName = (baseName == null || baseName.trim().isEmpty()) ? "BeamPointing" : baseName.trim();
    }

    public void setBaseName(String baseName) {
        this.baseName = baseName;
    }

    /** 设置图例上方的阶段标题（阶段名）；传 null 清除。 */
    public void setPhaseLabel(String label) {
        if (timeTrace != null) timeTrace.setLegendTitle(label);
        if (offX != null) offX.setLegendTitle(label);
        if (offY != null) offY.setLegendTitle(label);
        if (scatter != null) scatter.setLegendTitle(label);
    }

    /** 下载箭头（导出图标）的行为：默认导出当前阶段，可改为导出全部阶段。 */
    private Runnable exportAction = this::exportPng;
    private String exportTooltip = "导出 PNG 到预设文件夹（无对话框）：散点 / OffsetX / OffsetY";
    public void setExportAction(Runnable r) { this.exportAction = r; }
    public void setExportTooltip(String t) { this.exportTooltip = t; }

    public void setDistance(double mm) {
        this.distMm = mm > 0 ? mm : 0;
    }

    /** 切换当前展示的差分结果（阶段切换时调用）。 */
    public void rebind(DiffResult r) {
        this.result = r;
        rerenderAll();
    }

    public void show(JComponent extraControl) {
        distField.setToolTipText("输入传播距离(mm)，回车/失焦后散点 X/Y 与 RMS 圈转为 μrad（pointing）");
        if (distMm > 0) {
            distField.setText(String.valueOf(distMm));
        }
        java.util.function.DoubleSupplier read = () -> {
            try {
                double v = Double.parseDouble(distField.getText().trim());
                return v > 0 ? v : 0;
            } catch (Exception ex) {
                return 0;
            }
        };
        java.util.function.Consumer<Double> applyDist = (dmm) -> {
            distMm = dmm;
            renderScatter();
            printStats();
        };
        distField.addActionListener(e -> applyDist.accept(read.getAsDouble()));
        distField.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                applyDist.accept(read.getAsDouble());
            }
        });

        // 在「存储图形」软盘图标紧后方插入导出图标（与保存同类操作，不挤占右上角）
        scatter.addExportButton(exportTooltip, e -> exportAction.run());

        JPanel ctrl = buildControlPanel(extraControl);
        scatter.addTopRightControl(ctrl);

        rerenderAll();
        timeTrace.showPlot();
        offX.showPlot();
        offY.showPlot();
        scatter.showPlot();
    }

    private JPanel buildControlPanel(JComponent extra) {
        distField.setHorizontalAlignment(JTextField.RIGHT);
        Dimension d = new Dimension(50, distField.getPreferredSize().height);
        distField.setMinimumSize(d);
        distField.setPreferredSize(d);
        distField.setMaximumSize(d);

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0x999999)),
                BorderFactory.createEmptyBorder(2, 6, 2, 6)));
        panel.add(new JLabel("传播距离"));
        panel.add(Box.createHorizontalStrut(4));
        panel.add(distField);
        panel.add(Box.createHorizontalStrut(2));
        panel.add(new JLabel("mm"));
        if (extra != null) {
            panel.add(Box.createHorizontalStrut(4));
            panel.add(separator());
            panel.add(Box.createHorizontalStrut(4));
            panel.add(extra);
        }
        return panel;
    }

    private static JSeparator separator() {
        JSeparator sep = new JSeparator(SwingConstants.VERTICAL);
        sep.setForeground(new Color(0x999999));
        sep.setPreferredSize(new Dimension(1, 1));
        sep.setMaximumSize(new Dimension(1, Integer.MAX_VALUE));
        return sep;
    }

    private void rerenderAll() {
        renderTimeTrace();
        renderComp(offX, result.Dx, "Offset X / um", result.measName + "(校准)");
        renderComp(offY, result.Dy, "Offset Y / um", result.measName + "(校准)");
        renderScatter();
        printStats();
    }

    private void renderTimeTrace() {
        timeTrace.clearSeriesData();
        timeTrace.addLine(result.tg, result.Dx, Color.RED, "ΔX");
        timeTrace.addLine(result.tg, result.Dy, Color.GREEN, "ΔY");
        timeTrace.addLine(result.tg, result.D, Color.BLUE, "Δ(合成)");
        timeTrace.refresh();
    }

    private void renderComp(Plot p, double[] comp, String yLabel, String legend) {
        p.clearSeriesData();
        p.addLine(result.tg, comp, Color.BLUE, legend);
        p.refresh();
    }

    private void renderScatter() {
        int n = result.validSamples.size();
        double factor = distMm > 0 ? 1000.0 / distMm : 1.0;
        boolean urad = factor != 1.0;
        if (urad) {
            scatter.setXLabel("Offset X / urad");
            scatter.setYLabel("Offset Y / urad");
        } else {
            scatter.setXLabel("Offset X / um");
            scatter.setYLabel("Offset Y / um");
        }
        scatter.clearSeriesData();
        if (n == 0) {
            scatter.refresh();
            return;
        }
        double[] x = new double[n], y = new double[n];
        double sx = 0, sy = 0;
        for (int i = 0; i < n; i++) {
            x[i] = result.validSamples.get(i).x * factor;
            y[i] = result.validSamples.get(i).y * factor;
            sx += x[i];
            sy += y[i];
        }
        double cx = sx / n, cy = sy / n;
        double rmsUm = BeamPointingCore.centroidRmsUm(result.validSamples);
        String unit = urad ? "urad" : "um";
        String label = result.measName + "(校准)" + String.format(" (%.1f %s)", rmsUm * factor, unit);
        scatter.addScatter(x, y, Color.BLUE, label, Plot.MARKER_CIRCLE, true, 4f, 1f);
        scatter.addCircle(cx, cy, rmsUm * factor, Color.BLUE);
        scatter.refresh();
    }

    private void printStats() {
        double rmsUm = result.validSamples.isEmpty() ? Double.NaN : BeamPointingCore.centroidRmsUm(result.validSamples);
        double posUm = result.validSamples.isEmpty() ? Double.NaN : BeamPointingCore.positionUm(result.validSamples);
        double pointUrad = BeamPointingCore.pointingUrad(rmsUm, distMm);
        StringBuilder sb = new StringBuilder();
        if (!result.alignNote.isEmpty()) {
            sb.append(result.alignNote).append("\n");
        }
        sb.append("=== Laser Beam Pointing Stability (Differential) ===\n");
        if (result.validSamples.isEmpty()) {
            sb.append("（无有效差分点，无法统计 RMS / Mean）\n");
        } else {
            sb.append(String.format("RMS offset (computed) = %.3f um\n", rmsUm));
            sb.append(String.format("Mean offset (centroid) = %.3f um\n", posUm));
        }
        if (Double.isNaN(pointUrad)) {
            sb.append("pointing = -  (请在散点图右上角输入传播距离 mm)\n");
        } else {
            sb.append(String.format("pointing = %.3f urad\n", pointUrad));
        }
        double rmsMeas = result.rmsMeas;
        if (rmsMeas > 0) {
            sb.append(String.format("共模抑制 残余/原 = %.3f  (下降 %.1f%%)\n",
                    rmsUm / rmsMeas, (1 - rmsUm / rmsMeas) * 100));
        }
        if (diffLogWnd == null || !diffLogWnd.isShowing()) {
            diffLogWnd = new TextWindow("Beam Pointing Differential", sb.toString(), 600, 320);
        } else {
            diffLogWnd.getTextPanel().clear();
            diffLogWnd.getTextPanel().append(sb.toString());
            diffLogWnd.toFront();
        }
    }

    /** 将散点 / OffsetX / OffsetY 三张图导出为 PNG 到输出文件夹（当前设置、全幅视图）。 */
    public void exportPng() {
        if (outDir == null || !outDir.isDirectory()) {
            IJ.error("输出文件夹无效", "请先设置有效的输出文件夹");
            return;
        }
        try {
            resetAndSave(scatter, baseName + "_scatter");
            resetAndSave(offX, baseName + "_offsetX");
            resetAndSave(offY, baseName + "_offsetY");
            IJ.showStatus("已导出 3 张 PNG 到: " + outDir.getAbsolutePath());
        } catch (Exception ex) {
            IJ.error("导出失败", ex.getMessage());
        }
    }

    private void resetAndSave(Plot p, String name) throws Exception {
        p.resetView();
        p.savePng(new File(outDir, name + ".png"));
    }

    // ===================== 计算工具（纯静态，从 BeamPointingDiff_ 平移） =====================

    /** which: 0=t, 1=x, 2=y。 */
    private static double[] toArr(List<BeamPointingCore.Sample> s, int which) {
        double[] a = new double[s.size()];
        for (int i = 0; i < a.length; i++) {
            BeamPointingCore.Sample x = s.get(i);
            a[i] = which == 0 ? x.t : (which == 1 ? x.x : x.y);
        }
        return a;
    }

    /** 将 Data 裁剪到时间区间 [t0, t1]（闭区间），丢弃区间外采样点。 */
    private static BeamPointingCore.Data trimToRange(BeamPointingCore.Data d, double t0, double t1) {
        List<BeamPointingCore.Sample> kept = new ArrayList<>();
        for (BeamPointingCore.Sample s : d.samples) {
            if (s.t >= t0 && s.t <= t1) {
                kept.add(s);
            }
        }
        double rms = kept.isEmpty() ? Double.NaN : BeamPointingCore.centroidRmsUm(kept);
        return new BeamPointingCore.Data(kept, d.stats, rms, d.absStart);
    }

    /** 名义采样步长：取所有"非缺口"相邻间隔的均值(缺口段按 gapFactor×粗均值判定并排除)。 */
    private static double nominalStep(double[] t, double gapFactor) {
        double rough = (t[t.length - 1] - t[0]) / Math.max(1, t.length - 1);
        double thr = gapFactor * rough;
        double total = 0;
        int cnt = 0;
        for (int i = 0; i < t.length - 1; i++) {
            double d = t[i + 1] - t[i];
            if (d > 0 && d <= thr) {
                total += d;
                cnt++;
            }
        }
        return cnt > 0 ? total / cnt : rough;
    }

    /** Pearson 相关系数(仅对两数组均非 NaN 的点计算)；无有效点时返回 NaN。 */
    private static double corrValid(double[] a, double[] b) {
        int n = a.length;
        double ma = 0, mb = 0;
        int m = 0;
        for (int i = 0; i < n; i++) {
            if (!Double.isNaN(a[i]) && !Double.isNaN(b[i])) {
                ma += a[i];
                mb += b[i];
                m++;
            }
        }
        if (m == 0) {
            return Double.NaN;
        }
        ma /= m;
        mb /= m;
        double cov = 0, va = 0, vb = 0;
        for (int i = 0; i < n; i++) {
            if (!Double.isNaN(a[i]) && !Double.isNaN(b[i])) {
                double da = a[i] - ma, db = b[i] - mb;
                cov += da * db;
                va += da * da;
                vb += db * db;
            }
        }
        if (va == 0 || vb == 0) {
            return Double.NaN;
        }
        return cov / Math.sqrt(va * vb);
    }

    private static void appendCorr(StringBuilder sb, String axis, double corr) {
        if (Double.isNaN(corr)) {
            sb.append(String.format("  %s 轴：有效数据不足，无法计算相关性\n", axis));
            return;
        }
        String level;
        if (corr >= 0.7) {
            level = "共模良好，差分结果可信";
        } else if (corr >= 0.3) {
            level = "相关性一般，共模抑制有限，结果谨慎参考";
        } else {
            level = "相关性低，共模抑制不可靠，该轴差分结果请勿采信（请检查光路/参考臂）";
        }
        sb.append(String.format("  %s 轴：corr = %.3f —— %s\n", axis, corr, level));
    }

    private static int countNaN(double[] a) {
        int c = 0;
        for (double v : a) {
            if (Double.isNaN(v)) {
                c++;
            }
        }
        return c;
    }

    /** 线性插值重采样：t 单调递增；目标点位于缺口(相邻间隔超 maxStep)时返回 NaN，绝不插值伪造。 */
    private static double[] resample(double[] t, double[] v, double[] tg, double maxStep) {
        double[] out = new double[tg.length];
        int n = t.length, j = 0;
        for (int i = 0; i < tg.length; i++) {
            double x = tg[i];
            if (x <= t[0]) {
                out[i] = v[0];
                continue;
            }
            if (x >= t[n - 1]) {
                out[i] = v[n - 1];
                continue;
            }
            while (j < n - 2 && t[j + 1] < x) {
                j++;
            }
            if ((t[j + 1] - t[j]) > maxStep) {
                out[i] = Double.NaN;
                continue;
            }
            double f = (x - t[j]) / (t[j + 1] - t[j]);
            out[i] = v[j] + f * (v[j + 1] - v[j]);
        }
        return out;
    }
}
