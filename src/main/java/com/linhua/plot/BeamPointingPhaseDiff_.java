package com.linhua.plot;

import ij.IJ;
import ij.plugin.PlugIn;
import java.awt.Color;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.table.DefaultTableModel;

/**
 * Beam Pointing Differential 分阶段对比：选参考臂 + 测量臂 CSV 后，按绝对时间窗口把它们切成若干「阶段」，
 * 每个阶段单独做共模抑制差分（与 {@link BeamPointingDiff_} 同样的计算与 4 图 + Log），
 * 通过单窗口的「阶段」下拉切换；并支持一次性把所有阶段的散点 / OffsetX / OffsetY 导出为 PNG 到指定文件夹。
 *
 * <p>阶段分界输入格式与 {@link BeamPointingPhaseCompare_} 一致（同日 {@code "11:20, 18:00"} 或
 * 绝对日期 {@code "2026-11-10 11:20"}）；阶段名作图例与导出文件名前缀；差分核心计算委托
 * {@link BeamPointingDiffRender#compute}（纯函数，可单测）。</p>
 */
public class BeamPointingPhaseDiff_ implements PlugIn {

    @Override
    public void run(String arg) {
        File[] refFiles = PlotUtil.chooseFiles("选择参考臂 CSV（无器件）", "csv");
        if (refFiles.length == 0) {
            return;
        }
        File[] measFiles = PlotUtil.chooseFiles("选择测量臂 CSV（含被测器件）", "csv");
        if (measFiles.length == 0) {
            return;
        }
        try {
            BeamPointingCore.Data ref = BeamPointingCore.load(refFiles[0].getAbsolutePath());
            BeamPointingCore.Data meas = BeamPointingCore.load(measFiles[0].getAbsolutePath());
            String refName = stripExt(refFiles[0].getName());
            String measName = stripExt(measFiles[0].getName());

            // 全局绝对时间范围（含日期的秒）
            double minAbs = Math.min(ref.absStart, meas.absStart);
            long anchorDays = (long) Math.floor(minAbs / 86400.0);

            // ---- 对话框：阶段开始/结束/名称三列表格（可增删行、窗口可自由拉伸） ----
            DefaultTableModel phaseModel = BeamPointingPhaseCompare_.showPhaseDialog(
                    null, "Beam Pointing Differential 分阶段对比");
            if (phaseModel == null) {
                return; // 取消
            }

            List<BeamPointingPhaseCompare_.PhaseInterval> intervals;
            try {
                intervals = BeamPointingPhaseCompare_.readPhaseIntervals(phaseModel, anchorDays);
            } catch (Exception ex) {
                IJ.error("阶段时间解析失败", ex.getMessage());
                return;
            }
            // 输出目录默认与测量臂 CSV 同目录；输出前缀写死以区分「分阶段对比」与「分阶段差分」
            File outDir = measFiles[0].getParentFile();
            final String prefix = "PhaseDiff";

            // ---- 构建各阶段：仅表格中显式定义的阶段（开始 / 结束 / 名称） ----
            List<PhaseEntry> entries = new ArrayList<>();

            // 表格中显式定义的阶段：按行（开始, 结束, 名称）；「全部」也由用户手动作为一行填写
            for (int k = 0; k < intervals.size(); k++) {
                BeamPointingPhaseCompare_.PhaseInterval iv = intervals.get(k);
                String tag = iv.name.isEmpty() ? ("P" + (k + 1)) : iv.name;
                BeamPointingCore.Data rs = sliceData(ref, iv.start, iv.end);
                BeamPointingCore.Data ms = sliceData(meas, iv.start, iv.end);
                if (rs.samples.isEmpty() || ms.samples.isEmpty()) {
                    IJ.log("阶段 " + tag + " 缺少某一臂数据，已跳过");
                    continue;
                }
                BeamPointingDiffRender.DiffResult r;
                try {
                    r = BeamPointingDiffRender.compute(rs, ms, refName, measName);
                } catch (Exception ex) {
                    IJ.log("阶段 " + tag + " 差分失败：" + ex.getMessage());
                    continue;
                }
                if (r.validSamples.isEmpty()) {
                    continue;
                }
                String label = iv.name.isEmpty() ? tag : iv.name;
                String fileBase = sanitize(label);
                entries.add(new PhaseEntry(label, fileBase, r));
            }

            if (entries.isEmpty()) {
                IJ.error("没有可绘制的阶段", "所有阶段均无有效差分数据，请检查阶段分界与数据时间范围。");
                return;
            }

            // ---- 阶段切换器 ----
            JComboBox<String> phaseCombo = new JComboBox<>();
            for (PhaseEntry e : entries) {
                phaseCombo.addItem(e.label);
            }

            BeamPointingDiffRender render = new BeamPointingDiffRender(entries.get(0).result);
            render.setExport(outDir, prefix + "_" + entries.get(0).fileBase);
            render.setPhaseLabel(entries.get(0).label);

            // 「保存」图标右方的下载箭头 = 一键导出全部阶段 PNG（取消原独立的「导出全部阶段」按钮）
            render.setExportTooltip("导出全部阶段 PNG 到预设文件夹（无对话框）：散点 / OffsetX / OffsetY");
            render.setExportAction(() -> {
                int sel = phaseCombo.getSelectedIndex();
                for (PhaseEntry en : entries) {
                    render.rebind(en.result);
                    render.setBaseName(prefix + "_" + en.fileBase);
                    render.setPhaseLabel(en.label);
                    render.exportPng();
                }
                render.rebind(entries.get(sel).result);
                render.setBaseName(prefix + "_" + entries.get(sel).fileBase);
                render.setPhaseLabel(entries.get(sel).label);
            });

            JPanel extra = new JPanel();
            extra.setLayout(new BoxLayout(extra, BoxLayout.X_AXIS));
            extra.add(new JLabel("阶段："));
            extra.add(phaseCombo);

            phaseCombo.addActionListener(e -> {
                int idx = phaseCombo.getSelectedIndex();
                PhaseEntry en = entries.get(idx);
                render.rebind(en.result);
                render.setBaseName(prefix + "_" + en.fileBase);
                render.setPhaseLabel(en.label);
            });

            render.show(extra);
        } catch (Exception e) {
            IJ.error("处理失败", e.getMessage());
            e.printStackTrace();
        }
    }

    /** 把 Data 按绝对时间窗口 [a, b) 切片（复用 BeamPointingCore.slicePhase，t 重基为阶段相对时间）。 */
    private static BeamPointingCore.Data sliceData(BeamPointingCore.Data d, double a, double b) {
        List<BeamPointingCore.Sample> sl = BeamPointingCore.slicePhase(d.samples, d.absStart, a, b);
        double rms = sl.isEmpty() ? Double.NaN : BeamPointingCore.centroidRmsUm(sl);
        return new BeamPointingCore.Data(sl, d.stats, rms, d.absStart);
    }

    private static class PhaseEntry {
        final String label;
        final String fileBase;
        final BeamPointingDiffRender.DiffResult result;
        PhaseEntry(String label, String fileBase, BeamPointingDiffRender.DiffResult result) {
            this.label = label;
            this.fileBase = fileBase;
            this.result = result;
        }
    }

    private static String stripExt(String n) {
        int d = n.lastIndexOf('.');
        if (d > 0 && n.substring(d).equalsIgnoreCase(".csv")) {
            return n.substring(0, d);
        }
        return n;
    }

    private static String sanitize(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-'
                    || (c >= '\u4E00' && c <= '\u9FFF')) {
                b.append(c);
            } else {
                b.append('_');
            }
        }
        return b.toString();
    }
}
