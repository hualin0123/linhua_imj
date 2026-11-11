package com.linhua.plot;

import ij.IJ;
import ij.measure.ResultsTable;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.JTextField;
import javax.swing.SwingConstants;

/**
 * Beam Pointing 多文件对比的共享渲染器：把「一组序列」渲染为 4 个图（合成时间轨迹 / OffsetX 时间轨迹 /
 * OffsetY 时间轨迹 / X–Y 散点）+ 结果表，并提供「对齐中心」「传播距离」「导出 PNG」交互。
 *
 * <p>原先 {@code BeamPointingCompare_} 的渲染逻辑全部内联在插件里；抽成本类后，
 * {@code BeamPointingCompare_}（全部数据）与 {@code BeamPointingPhaseCompare_}（分阶段）共用同一套渲染，
 * 避免重复且保证视觉一致。序列以 {@link SeriesView} 承载，渲染是纯展示、不含计算，
 * 统计由 {@link BeamPointingCore#centroidStats} 等纯函数预先算好再传入。</p>
 */
public class BeamPointingCompareRender {

    /** 一条对比序列（一个文件 / 一个文件×阶段）：名称、颜色、相对时间样本、以及预计算的统计。 */
    public static class SeriesView {
        public final String name;
        public final Color color;
        public final List<BeamPointingCore.Sample> samples; // 相对时间（文件相对或阶段相对）
        public final double xmean, ymean, rmean, crms;       // 绕质心统计（与显示对齐无关）

        public SeriesView(String name, Color color, List<BeamPointingCore.Sample> samples,
                          double xmean, double ymean, double rmean, double crms) {
            this.name = name;
            this.color = color;
            this.samples = samples;
            this.xmean = xmean;
            this.ymean = ymean;
            this.rmean = rmean;
            this.crms = crms;
        }
    }

    /** 对比散点用不同形状区分各序列（颜色叠加时仍可辨形）。 */
    private static final int[] SHAPES = {
        Plot.MARKER_CIRCLE, Plot.MARKER_TRIANGLE, Plot.MARKER_SQUARE, Plot.MARKER_DIAMOND
    };

    /** 给颜色加透明度（0–255），便于多序列叠加时混色而非遮盖。 */
    private static Color withAlpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
    }

    private List<SeriesView> views;
    private final Plot trace, traceX, traceY, scatter;
    private final ResultsTable resultsTable = new ResultsTable();
    private final JCheckBox alignBox = new JCheckBox("对齐中心", false);
    private final JTextField distField = new JTextField(4);
    private File outDir;
    private String baseName = "BeamPointing";

    public BeamPointingCompareRender(List<SeriesView> views) {
        this.views = views;

        trace = new Plot("Beam Pointing Compare - Time Trace", "Time / s", "Offset / um");
        PlotUtil.applyStyle(trace, "Time / s", "Offset / um", 0, 1, Plot.LEGEND_UPPER_LEFT, true, false, true);

        traceX = new Plot("Beam Pointing Compare - Offset X Time Trace", "Time / s", "Offset X / um");
        PlotUtil.applyStyle(traceX, "Time / s", "Offset X / um", 0, 1, Plot.LEGEND_UPPER_LEFT, true, false, true);

        traceY = new Plot("Beam Pointing Compare - Offset Y Time Trace", "Time / s", "Offset Y / um");
        PlotUtil.applyStyle(traceY, "Time / s", "Offset Y / um", 0, 1, Plot.LEGEND_UPPER_LEFT, true, false, true);

        scatter = new Plot("Beam Pointing Compare - X-Y Scatter", "Offset X / um", "Offset Y / um");
        PlotUtil.applyStyle(scatter, "Offset X / um", "Offset Y / um", 1, 1, Plot.LEGEND_UPPER_LEFT, true, false, true);
    }

    /** 设置批量导出参数：输出文件夹与文件名前缀。 */
    public void setExport(File outDir, String baseName) {
        this.outDir = outDir;
        this.baseName = (baseName == null || baseName.trim().isEmpty()) ? "BeamPointing" : baseName.trim();
    }

    /** 切换当前展示的序列集合（阶段切换时调用）。 */
    public void rebind(List<SeriesView> newViews) {
        this.views = newViews;
        rerenderAll();
    }

    /** 设置当前导出文件名前缀（阶段切换/批量导出前调用）。 */
    public void setBaseName(String baseName) {
        this.baseName = baseName;
    }

    /** 设置图例上方的阶段标题（阶段名）；传 null 清除。 */
    public void setPhaseLabel(String label) {
        if (trace != null) trace.setLegendTitle(label);
        if (traceX != null) traceX.setLegendTitle(label);
        if (traceY != null) traceY.setLegendTitle(label);
        if (scatter != null) scatter.setLegendTitle(label);
    }

    /** 下载箭头（导出图标）的行为：默认导出当前阶段，可改为导出全部阶段。 */
    private Runnable exportAction = this::exportPng;
    private String exportTooltip = "导出 PNG 到预设文件夹（无对话框）：散点 / OffsetX / OffsetY";
    public void setExportAction(Runnable r) { this.exportAction = r; }
    public void setExportTooltip(String t) { this.exportTooltip = t; }

    /** 创建窗口并展示；extraControl 为可选附加控件（如阶段切换器），置于散点图工具条右侧。 */
    public void show(JComponent extraControl) {
        alignBox.setToolTipText("勾选后各序列减去自身质心，便于比较指向抖动");
        distField.setToolTipText("输入测量面到基准面的传播距离(mm)，回车或失焦后散点 X/Y 转为 μrad（pointing）");

        java.util.function.DoubleSupplier readDist = () -> {
            try {
                double v = Double.parseDouble(distField.getText().trim());
                return v > 0 ? v : 0;
            } catch (Exception ex) {
                return 0;
            }
        };

        // 对齐中心：重渲染所有图（轨迹/散点）
        alignBox.addActionListener(e -> rerenderAll());

        // 传播距离：仅影响散点与结果表（轨迹恒为 μm）
        java.util.function.Consumer<Double> applyDist = (dmm) -> {
            renderScatter(scatter, alignBox.isSelected(), dmm);
            fillResultsTable(dmm);
            showResultsTable();
        };
        distField.addActionListener(e -> applyDist.accept(readDist.getAsDouble()));
        distField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                applyDist.accept(readDist.getAsDouble());
            }
        });

        // 在「存储图形」软盘图标紧后方插入导出图标（与保存同类操作，不挤占右上角）
        scatter.addExportButton(exportTooltip, e -> exportAction.run());

        JPanel ctrl = buildControlPanel(extraControl);
        scatter.addTopRightControl(ctrl);

        rerenderAll();
        trace.showPlot();
        traceX.showPlot();
        traceY.showPlot();
        scatter.showPlot();
    }

    /** 构建散点图右上角控件：对齐中心 | 传播距离 mm [额外控件（如阶段切换）]。导出 PNG 已移到左侧工具栏图标。 */
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

        panel.add(alignBox);
        panel.add(Box.createHorizontalStrut(4));
        panel.add(separator());
        panel.add(Box.createHorizontalStrut(4));
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
        boolean al = alignBox.isSelected();
        double dmm = readDistValue();
        renderTrace(trace, al);
        renderTraceComp(traceX, 0, al);
        renderTraceComp(traceY, 1, al);
        renderScatter(scatter, al, dmm);
        fillResultsTable(dmm);
        showResultsTable();
    }

    private double readDistValue() {
        try {
            double v = Double.parseDouble(distField.getText().trim());
            return v > 0 ? v : 0;
        } catch (Exception ex) {
            return 0;
        }
    }

    /** 时间轨迹：每序列叠加合成偏移 (r)，align 时减去各自 r 均值。 */
    private void renderTrace(Plot p, boolean align) {
        p.clearSeriesData();
        for (SeriesView v : views) {
            List<BeamPointingCore.Sample> s = v.samples;
            int m = s.size();
            double[] t = new double[m], r = new double[m];
            for (int k = 0; k < m; k++) {
                t[k] = s.get(k).t;
                r[k] = align ? s.get(k).r - v.rmean : s.get(k).r;
            }
            p.addLine(t, r, v.color, v.name);
        }
        p.refresh();
    }

    /** 时间轨迹（分量）：每序列叠加 X 或 Y 偏移，align 时减去各自分量均值；which: 0=X, 1=Y。 */
    private void renderTraceComp(Plot p, int which, boolean align) {
        p.clearSeriesData();
        for (SeriesView v : views) {
            List<BeamPointingCore.Sample> s = v.samples;
            int m = s.size();
            double[] t = new double[m], val = new double[m];
            double mean = (which == 0) ? v.xmean : v.ymean;
            for (int k = 0; k < m; k++) {
                double raw = (which == 0) ? s.get(k).x : s.get(k).y;
                val[k] = align ? raw - mean : raw;
                t[k] = s.get(k).t;
            }
            p.addLine(t, val, v.color, v.name);
        }
        p.refresh();
    }

    /** X–Y 散点：半透明异形点 + 每序列的 RMS 圈；align 时减质心并移圈到原点；distMm>0 时 X/Y 转 μrad。 */
    private void renderScatter(Plot p, boolean align, double distMm) {
        double factor = distMm > 0 ? 1000.0 / distMm : 1.0;
        if (factor != 1.0) {
            p.setXLabel("Offset X / urad");
            p.setYLabel("Offset Y / urad");
        } else {
            p.setXLabel("Offset X / um");
            p.setYLabel("Offset Y / um");
        }
        p.clearSeriesData();
        for (int i = 0; i < views.size(); i++) {
            SeriesView v = views.get(i);
            List<BeamPointingCore.Sample> s = v.samples;
            int m = s.size();
            double[] x = new double[m], y = new double[m];
            for (int k = 0; k < m; k++) {
                x[k] = (align ? s.get(k).x - v.xmean : s.get(k).x) * factor;
                y[k] = (align ? s.get(k).y - v.ymean : s.get(k).y) * factor;
            }
            int shape = SHAPES[i % SHAPES.length];
            double rmsVal = v.crms * factor;
            String unit = (factor != 1.0) ? "urad" : "um";
            String label = v.name + String.format(" (%.1f %s)", rmsVal, unit);
            p.addScatter(x, y, withAlpha(v.color, 70), label, shape, false, 4f, 1f);
        }
        for (SeriesView v : views) {
            double cx = (align ? 0 : v.xmean) * factor;
            double cy = (align ? 0 : v.ymean) * factor;
            p.addCircle(cx, cy, v.crms * factor, v.color);
        }
        p.refresh();
    }

    /** 按当前传播距离填充结果表：pointing = 空间 RMS × 1000/L（L<=0 时省略该列），mean offset = 质心偏移。 */
    private void fillResultsTable(double distMm) {
        resultsTable.reset();
        boolean hasDist = distMm > 0;
        for (int i = 0; i < views.size(); i++) {
            SeriesView v = views.get(i);
            double posUm = Math.hypot(v.xmean, v.ymean);
            resultsTable.incrementCounter();
            if (hasDist) {
                resultsTable.addValue("Pointing/urad", v.crms * 1000.0 / distMm);
            }
            resultsTable.addValue("RMS/um", v.crms);
            resultsTable.addValue("Mean offset/um", posUm);
            resultsTable.setValue("File", i, v.name);
        }
    }

    private void showResultsTable() {
        resultsTable.show("Beam Pointing Compare");
    }

    /** 将散点 / OffsetX / OffsetY 三张图导出为 PNG 到输出文件夹（当前设置、全幅视图）。 */
    public void exportPng() {
        if (outDir == null || !outDir.isDirectory()) {
            IJ.error("输出文件夹无效", "请先设置有效的输出文件夹");
            return;
        }
        try {
            resetAndSave(scatter, baseName + "_scatter");
            resetAndSave(traceX, baseName + "_offsetX");
            resetAndSave(traceY, baseName + "_offsetY");
            IJ.showStatus("已导出 3 张 PNG 到: " + outDir.getAbsolutePath());
        } catch (Exception ex) {
            IJ.error("导出失败", ex.getMessage());
        }
    }

    private void resetAndSave(Plot p, String name) throws Exception {
        p.resetView();
        p.savePng(new File(outDir, name + ".png"));
    }
}
