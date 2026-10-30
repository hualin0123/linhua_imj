package com.linhua.plot;

import ij.IJ;
import ij.plugin.PlugIn;
import java.awt.Color;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.JCheckBox;
import javax.swing.JPanel;
import javax.swing.JTextField;
import ij.measure.ResultsTable;

/**
 * 多文件 Beam Pointing 对比：弹多选对话框选多个 CSV，
 * 按相对时间叠加各次测量的合成偏移轨迹与 X–Y 位置云，并在 Log 输出跨 run 的 RMS/指向/位置统计。
 * 可选「对齐各 run 中心」（减去各自质心）以便纯粹比较指向抖动；
 * X–Y 对比散点以半透明异形标记叠加，并标每 run 的 RMS 圈；
 * 散点图右上角可输入传播距离（mm），将 X/Y 从 μm 实时转为 μrad（pointing）。
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

        try {
            compare(files);
        } catch (Exception e) {
            IJ.error("解析失败", e.getMessage());
            e.printStackTrace();
        }
    }

    private void compare(List<File> files) throws Exception {
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

        // 预计算每个 run 的质心与基于质心的 RMS（正确稳定性指标，与显示对齐无关）
        double[] xmean = new double[n], ymean = new double[n], rmean = new double[n], crms = new double[n];
        for (int i = 0; i < n; i++) {
            List<BeamPointingCore.Sample> s = datas.get(i).samples;
            int m = s.size();
            double sx = 0, sy = 0, sr = 0;
            for (int k = 0; k < m; k++) {
                sx += s.get(k).x;
                sy += s.get(k).y;
                sr += s.get(k).r;
            }
            xmean[i] = sx / m;
            ymean[i] = sy / m;
            rmean[i] = sr / m;
            double ss = 0;
            for (int k = 0; k < m; k++) {
                double dx = s.get(k).x - xmean[i], dy = s.get(k).y - ymean[i];
                ss += dx * dx + dy * dy;
            }
            crms[i] = Math.sqrt(ss / m);
        }

        // ---- 时间轨迹窗口（单位固定为 μm） ----
        Plot trace = new Plot("Beam Pointing Compare - Time Trace", "Time / s", "Offset / um");
        PlotUtil.applyStyle(trace, "Time / s", "Offset / um", 0, 1, Plot.LEGEND_UPPER_LEFT, true, false, true);
        renderTrace(trace, datas, names, colors, rmean, false);

        // ---- X–Y 散点窗口（右上角放“对齐中心”与“传播距离”控件） ----
        Plot scatter = new Plot("Beam Pointing Compare - X-Y Scatter", "Offset X / um", "Offset Y / um");
        PlotUtil.applyStyle(scatter, "Offset X / um", "Offset Y / um", 1, 1, Plot.LEGEND_UPPER_LEFT, true, false, true);

        JCheckBox alignBox = new JCheckBox("对齐中心", false);
        alignBox.setToolTipText("勾选后各 run 减去自身质心，便于比较指向抖动");
        JTextField distField = new JTextField(4);
        distField.setToolTipText("输入测量面到基准面的传播距离(mm)，回车或失焦后散点 X/Y 转为 μrad（pointing）");

        // 解析传播距离（mm）；<=0 表示仍用 μm 单位
        java.util.function.DoubleSupplier readDist = () -> {
            try {
                double v = Double.parseDouble(distField.getText().trim());
                return v > 0 ? v : 0;
            } catch (Exception ex) {
                return 0;
            }
        };

        // ---- 结果表格窗口（ImageJ 标准 ResultsTable，可被复用并 Save As CSV） ----
        ResultsTable resultsTable = newResultsTable();
        fillResultsTable(resultsTable, datas, names, xmean, ymean, crms, 0);
        showResultsTable(resultsTable);

        // 应用距离：重绘散点（X/Y 转 μrad）并刷新结果表
        java.util.function.Consumer<Double> applyDist = (dmm) -> {
            renderScatter(scatter, datas, names, colors, xmean, ymean, crms, alignBox.isSelected(), dmm);
            fillResultsTable(resultsTable, datas, names, xmean, ymean, crms, dmm);
            showResultsTable(resultsTable);
        };

        alignBox.addActionListener(e -> {
            double dmm = readDist.getAsDouble();
            renderTrace(trace, datas, names, colors, rmean, alignBox.isSelected());
            renderScatter(scatter, datas, names, colors, xmean, ymean, crms, alignBox.isSelected(), dmm);
        });
        distField.addActionListener(e -> applyDist.accept(readDist.getAsDouble()));
        distField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) { applyDist.accept(readDist.getAsDouble()); }
        });

        JPanel extra = PlotUtil.buildOptionsPanel(alignBox, distField);
        scatter.addTopRightControl(extra);

        // 初始：μm 单位、不对齐
        renderScatter(scatter, datas, names, colors, xmean, ymean, crms, false, 0);

        trace.showPlot();
        scatter.showPlot();
    }

    /** 创建对比结果表（ImageJ 标准 ResultsTable，可被复用并 Save As CSV）。 */
    private static ResultsTable newResultsTable() {
        return new ResultsTable();
    }

    /** 按当前传播距离填充结果表：pointing = 空间 RMS × 1000/L（L<=0 时省略该列），mean offset = 质心偏移。 */
    private static void fillResultsTable(ResultsTable rt, List<BeamPointingCore.Data> datas, List<String> names,
                                         double[] xmean, double[] ymean, double[] crms, double distMm) {
        rt.reset();
        boolean hasDist = distMm > 0;
        for (int i = 0; i < datas.size(); i++) {
            double posUm = Math.hypot(xmean[i], ymean[i]);
            rt.incrementCounter();
            if (hasDist) {
                rt.addValue("Pointing/urad", crms[i] * 1000.0 / distMm);
            }
            rt.addValue("RMS/um", crms[i]);
            rt.addValue("Mean offset/um", posUm);
            rt.setValue("File", i, names.get(i));
        }
    }

    /** 显示/刷新对比结果表窗口（ImageJ Results 风格，同名窗口自动复用刷新）。 */
    private static void showResultsTable(ResultsTable rt) {
        rt.show("Beam Pointing Compare");
    }

    /** 时间轨迹：每 run 叠加合成偏移 (r)，align 时减去各自 r 均值。 */
    private void renderTrace(Plot p, List<BeamPointingCore.Data> datas, List<String> names,
                             Color[] colors, double[] rmean, boolean align) {
        p.clearSeriesData();
        int n = datas.size();
        for (int i = 0; i < n; i++) {
            List<BeamPointingCore.Sample> s = datas.get(i).samples;
            int m = s.size();
            double[] t = new double[m], r = new double[m];
            for (int k = 0; k < m; k++) {
                t[k] = s.get(k).t;
                r[k] = align ? s.get(k).r - rmean[i] : s.get(k).r;
            }
            p.addLine(t, r, colors[i], names.get(i));
        }
        p.refresh();
    }

    /** X–Y 散点：半透明异形点 + 每 run 的 RMS 圈；align 时减质心并移圈到原点；distMm>0 时 X/Y 转 μrad。 */
    private void renderScatter(Plot p, List<BeamPointingCore.Data> datas, List<String> names,
                               Color[] colors, double[] xmean, double[] ymean, double[] crms,
                               boolean align, double distMm) {
        double factor = distMm > 0 ? 1000.0 / distMm : 1.0;
        if (factor != 1.0) {
            p.setXLabel("Offset X / urad");
            p.setYLabel("Offset Y / urad");
        } else {
            p.setXLabel("Offset X / um");
            p.setYLabel("Offset Y / um");
        }
        p.clearSeriesData();
        int n = datas.size();
        for (int i = 0; i < n; i++) {
            List<BeamPointingCore.Sample> s = datas.get(i).samples;
            int m = s.size();
            double[] x = new double[m], y = new double[m];
            for (int k = 0; k < m; k++) {
                x[k] = (align ? s.get(k).x - xmean[i] : s.get(k).x) * factor;
                y[k] = (align ? s.get(k).y - ymean[i] : s.get(k).y) * factor;
            }
            int shape = SHAPES[i % SHAPES.length];
            p.addScatter(x, y, withAlpha(colors[i], 70), names.get(i), shape, false, 4f, 1f);
        }
        // RMS 圈置于最上层（在所有散点之后绘制）
        for (int i = 0; i < n; i++) {
            double cx = (align ? 0 : xmean[i]) * factor;
            double cy = (align ? 0 : ymean[i]) * factor;
            p.addCircle(cx, cy, crms[i] * factor, colors[i]);
        }
        p.refresh();
    }
}
