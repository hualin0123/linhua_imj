package com.linhua.plot;

import ij.IJ;
import ij.plugin.PlugIn;
import java.awt.Color;
import java.awt.Dimension;
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
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Frame;
import javax.swing.JDialog;
import javax.swing.JComponent;
import java.util.concurrent.atomic.AtomicBoolean;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import javax.swing.AbstractAction;
import javax.swing.KeyStroke;
import java.util.regex.Pattern;

/**
 * Beam Pointing 分阶段对比：选多个 CSV 后，按时间窗口把它们切成若干「阶段」（phase），
 * 每个阶段单独比较（与 {@link BeamPointingCompare_} 同样的 4 图 + 结果表），
 * 通过单窗口的「阶段」下拉切换；并支持一次性把所有阶段的散点 / OffsetX / OffsetY 导出为 PNG 到指定文件夹。
 *
 * <p>阶段分界用对话框输入，例如 {@code "11:20, 18:00"}（同日 HH:MM，日期自动取数据首个采样日）；
 * 跨午夜/多天可填绝对日期时间 {@code "2026-11-10 11:20, 2026-11-10 18:00"}。阶段名可填（逗号分隔），
 * 留空则用 P1/P2…；阶段名同时作为图例与导出 PNG 的文件名前缀，免去手动切分 CSV 与重命名。</p>
 *
 * <p>纯计算（阶段切分、统计、分界解析）集中在 {@link BeamPointingCore#slicePhase} /
 * {@link BeamPointingCore#centroidStats} 与 {@link #parseCuts}，可被单测直接断言（见 VERIFICATION.md）。</p>
 */
public class BeamPointingPhaseCompare_ implements PlugIn {

    @Override
    public void run(String arg) {
        File[] filesArr = PlotUtil.chooseFiles("选择 Beam Pointing CSV（可多选）", "csv");
        if (filesArr.length == 0) {
            return;
        }
        List<File> files = java.util.Arrays.asList(filesArr);

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
        Color[] colors = PlotUtil.assignColors(n);

        // 全局绝对时间范围（含日期的秒）
        double minAbs = Double.POSITIVE_INFINITY;
        for (BeamPointingCore.Data d : datas) {
            minAbs = Math.min(minAbs, d.absStart);
        }
        long anchorDays = (long) Math.floor(minAbs / 86400.0);

        // ---- 对话框：阶段开始/结束/名称三列表格（可增删行、窗口可自由拉伸） ----
        DefaultTableModel phaseModel = showPhaseDialog(null, "Beam Pointing 分阶段对比");
        if (phaseModel == null) {
            return; // 取消
        }

        // 解析表格中显式定义的阶段（开始/结束/名称）
        List<PhaseInterval> intervals;
        try {
            intervals = readPhaseIntervals(phaseModel, anchorDays);
        } catch (Exception ex) {
            IJ.error("阶段时间解析失败", ex.getMessage());
            return;
        }
        // 输出目录默认与首个 CSV 同目录；输出前缀写死以区分「分阶段对比」与「分阶段差分」
        File outDir = files.get(0).getParentFile();
        final String prefix = "PhaseComp";

        // ---- 构建各阶段视图：仅表格中显式定义的阶段（开始 / 结束 / 名称） ----
        List<PhaseEntry> entries = new ArrayList<>();

        // 表格中显式定义的阶段：按行（开始, 结束, 名称）；「全部」也由用户手动作为一行填写
        for (int k = 0; k < intervals.size(); k++) {
            PhaseInterval iv = intervals.get(k);
            List<BeamPointingCompareRender.SeriesView> views = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                // 每个文件按绝对时间窗口切片并重置为阶段相对时间
                List<BeamPointingCore.Sample> slice = BeamPointingCore.slicePhase(
                        datas.get(i).samples, datas.get(i).absStart, iv.start, iv.end);
                if (slice.isEmpty()) {
                    continue;
                }
                double[] st = BeamPointingCore.centroidStats(slice);
                views.add(new BeamPointingCompareRender.SeriesView(
                        names.get(i), colors[i], slice, st[0], st[1], st[2], st[3]));
            }
            if (views.isEmpty()) {
                IJ.log(String.format("阶段 %s（%s ~ %s）无有效数据，已跳过",
                        iv.name.isEmpty() ? ("P" + (k + 1)) : iv.name,
                        fmtAbs(iv.start), fmtAbs(iv.end)));
                continue;
            }
            String label = iv.name.isEmpty() ? ("P" + (k + 1)) : iv.name;
            String fileBase = sanitize(label);
            entries.add(new PhaseEntry(label, fileBase, views));
        }

        if (entries.isEmpty()) {
            IJ.error("没有可绘制的阶段", "所有阶段均无有效数据，请检查阶段分界与数据时间范围。");
            return;
        }

        // ---- 构建阶段切换器 + 导出全部按钮 ----
        JComboBox<String> phaseCombo = new JComboBox<>();
        for (PhaseEntry e : entries) {
            phaseCombo.addItem(e.label);
        }

        BeamPointingCompareRender render = new BeamPointingCompareRender(entries.get(0).views);
        render.setExport(outDir, prefix + "_" + entries.get(0).fileBase);
        render.setPhaseLabel(entries.get(0).label);

        // 「保存」图标右方的下载箭头 = 一键导出全部阶段 PNG（取消原独立的「导出全部阶段」按钮）
        render.setExportTooltip("导出全部阶段 PNG 到预设文件夹（无对话框）：散点 / OffsetX / OffsetY");
        render.setExportAction(() -> {
            int sel = phaseCombo.getSelectedIndex();
            for (PhaseEntry en : entries) {
                render.rebind(en.views);
                render.setBaseName(prefix + "_" + en.fileBase);
                render.setPhaseLabel(en.label);
                render.exportPng();
            }
            // 恢复当前选择
            render.rebind(entries.get(sel).views);
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
            render.rebind(en.views);
            render.setBaseName(prefix + "_" + en.fileBase);
            render.setPhaseLabel(en.label);
        });

        render.show(extra);
    }

    /** 一个阶段的展示数据。 */
    private static class PhaseEntry {
        final String label;     // 显示名（可含中文）
        final String fileBase;  // 文件名安全前缀（仅 [A-Za-z0-9._-]）
        final List<BeamPointingCompareRender.SeriesView> views;
        PhaseEntry(String label, String fileBase, List<BeamPointingCompareRender.SeriesView> views) {
            this.label = label;
            this.fileBase = fileBase;
            this.views = views;
        }
    }

    /** 把文件名非法字符替换为下划线；保留字母/数字/._- 以及中文（CJK 统一表意文字）。 */
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

    /** 把绝对秒格式化为可读时间（用于日志提示）。 */
    private static String fmtAbs(double abs) {
        long days = (long) Math.floor(abs / 86400.0);
        double tod = abs - days * 86400.0;
        long h = (long) (tod / 3600);
        long m = (long) ((tod % 3600) / 60);
        long s = (long) (tod % 60);
        return String.format("%d天 %02d:%02d:%02d", days, h, m, s);
    }

    /** 一个显式阶段区间（绝对秒）：开始、结束、名称。 */
    public static final class PhaseInterval {
        public final double start;
        public final double end;
        public final String name;
        public PhaseInterval(double start, double end, String name) {
            this.start = start;
            this.end = end;
            this.name = name;
        }
    }

    /** 绝对日期正则：年-月-日 或 年/月/日（年在前，分隔符 - 或 / 均可）。 */
    private static final Pattern DATE_RE = Pattern.compile("(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})");

    /**
     * 解析单个时间点为绝对秒，对分隔符/格式尽量容错：
     * <ul>
     *   <li>{@code "HH:MM"} / {@code "HH:MM:SS"} 取 anchorDays 当日；</li>
     *   <li>{@code "YYYY-MM-DD"} 或 {@code "YYYY/MM/DD"}（分隔符 - 与 / 均可）为绝对日期；</li>
     *   <li>日期与时间可用空格或 T 连接，如 {@code "2026/11/10 11:20"}、{@code "2026-11-10T11:20"}；</li>
     *   <li>全角斜杠 {@code ／} 自动规范为 {@code /}；日期缺省时间为 00:00。</li>
     * </ul>
     */
    public static double parseTime(String tok, long anchorDays) throws Exception {
        tok = tok.trim().replace('／', '/').replaceAll("[,，]$", "");
        long days = anchorDays;
        String timePart = tok;
        java.util.regex.Matcher dm = DATE_RE.matcher(tok);
        if (dm.find()) {
            days = daysFromDate(dm.group(0));
            String rest = tok.substring(dm.end()).replaceFirst("^T", "").trim();
            timePart = rest.isEmpty() ? "00:00" : rest;
        }
        double tod = parseTOD(timePart);
        return days * 86400.0 + tod;
    }

    /** 从三列表格（开始/结束/名称）读取阶段区间；开始或结束为空的整行忽略。 */
    public static List<PhaseInterval> readPhaseIntervals(DefaultTableModel model, long anchorDays) throws Exception {
        List<PhaseInterval> out = new ArrayList<>();
        for (int r = 0; r < model.getRowCount(); r++) {
            String s = cell(model, r, 0);
            String e = cell(model, r, 1);
            if (s.isEmpty() || e.isEmpty()) {
                continue;
            }
            double start = parseTime(s, anchorDays);
            double end = parseTime(e, anchorDays);
            String name = cell(model, r, 2);
            out.add(new PhaseInterval(start, end, name));
        }
        return out;
    }

    private static String cell(DefaultTableModel m, int r, int c) {
        Object o = m.getValueAt(r, c);
        return (o == null) ? "" : o.toString().trim();
    }

    /**
     * 弹出一个可自由拉伸的对话框，用「开始 / 结束 / 名称」三列表格定义阶段。
     * 返回表格模型（确定）或 null（取消）；「全部(All)」阶段不在表中，由调用方始终绘制。
     */
    public static DefaultTableModel showPhaseDialog(Component parent, String title) {
        String[] cols = {"阶段开始", "阶段结束", "阶段名称(可选)"};
        DefaultTableModel model = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return true; }
        };
        for (int i = 0; i < 3; i++) {
            model.addRow(new Object[]{"", "", ""});
        }
        JTable table = new JTable(model);
        table.setRowHeight(22);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        table.setToolTipText("每行一个阶段：开始/结束时间（例 11:20 或 2026/11/10 11:20，支持 - 与 / 分隔）；"
                + "名称可留空。选中单元格后按 Ctrl+V 可从 Excel 粘贴多行。");
        addTablePasteSupport(table, model);
        JScrollPane sp = new JScrollPane(table);
        sp.setPreferredSize(new Dimension(480, 220));

        JButton addRow = new JButton("＋ 增加一行");
        addRow.addActionListener(e -> model.addRow(new Object[]{"", "", ""}));
        JButton delRow = new JButton("－ 删除一行");
        delRow.addActionListener(e -> {
            if (model.getRowCount() > 1) {
                model.removeRow(model.getRowCount() - 1);
            }
        });
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        btnRow.add(addRow);
        btnRow.add(delRow);

        JPanel content = new JPanel(new BorderLayout(0, 6));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        content.add(new JLabel("<html>每行一个阶段；时间格式 11:20 或 2026-11-10 11:20，名称可留空。</html>"),
                BorderLayout.NORTH);
        content.add(sp, BorderLayout.CENTER);
        content.add(btnRow, BorderLayout.SOUTH);

        JDialog dlg = new JDialog((Frame) null, title, true);
        dlg.setResizable(true);
        dlg.getContentPane().setLayout(new BorderLayout(0, 8));
        dlg.getContentPane().add(content, BorderLayout.CENTER);
        JButton ok = new JButton("确定");
        JButton cancel = new JButton("取消");
        AtomicBoolean confirmed = new AtomicBoolean(false);
        ok.addActionListener(e -> { confirmed.set(true); dlg.dispose(); });
        cancel.addActionListener(e -> dlg.dispose());
        JPanel btnBar = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        btnBar.add(ok);
        btnBar.add(cancel);
        dlg.getContentPane().add(btnBar, BorderLayout.SOUTH);

        dlg.pack();
        dlg.setLocationRelativeTo(parent);
        dlg.setVisible(true);
        return confirmed.get() ? model : null;
    }

    /** 给阶段表格挂上 Ctrl+V 粘贴：从 Excel/CSV 复制的多行（制表符分隔）可整块填入，自动增行。 */
    private static void addTablePasteSupport(JTable table, DefaultTableModel model) {
        KeyStroke ks = KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK);
        table.getInputMap(JComponent.WHEN_FOCUSED).put(ks, "pasteClip");
        table.getActionMap().put("pasteClip", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                pasteFromClipboard(table, model);
            }
        });
    }

    /** 把系统剪贴板中的文本（Excel 复制为 制表符+换行）写入表格，从选中单元格开始，必要时自动加行。 */
    private static void pasteFromClipboard(JTable table, DefaultTableModel model) {
        Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
        Transferable t = cb.getContents(null);
        if (t == null || !t.isDataFlavorSupported(DataFlavor.stringFlavor)) {
            return;
        }
        String clip;
        try {
            clip = (String) t.getTransferData(DataFlavor.stringFlavor);
        } catch (Exception ex) {
            return;
        }
        if (clip == null) {
            return;
        }
        clip = clip.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = clip.split("\n", -1);
        int startRow = table.getSelectedRow();
        int startCol = table.getSelectedColumn();
        if (startRow < 0) startRow = 0;
        if (startCol < 0) startCol = 0;
        int r = startRow;
        for (String line : lines) {
            if (line.isEmpty()) {
                continue; // 跳过空行（Excel 复制常带尾随空行）
            }
            String[] cells = line.split("\t", -1);
            while (r >= model.getRowCount()) {
                model.addRow(new Object[]{"", "", ""});
            }
            for (int j = 0; j < cells.length && (startCol + j) < model.getColumnCount(); j++) {
                model.setValueAt(cells[j].trim(), r, startCol + j);
            }
            r++;
        }
        table.repaint();
    }

    /**
     * 解析阶段分界文本为绝对秒切点数组（升序）。
     * 每个切点可写为 {@code "HH:MM"}（取 anchorDays 当日）或 {@code "YYYY-MM-DD HH:MM"}（绝对日期）。
     * 逗号（中英文）分隔；空文本返回空数组（仅「全部」一个阶段）。
     */
    public static double[] parseCuts(String text, long anchorDays) throws Exception {
        if (text == null) {
            return new double[0];
        }
        String[] toks = text.split("[,，]");
        List<Double> out = new ArrayList<>();
        for (String tok : toks) {
            tok = tok.trim();
            if (tok.isEmpty()) {
                continue;
            }
            out.add(parseTime(tok, anchorDays));
        }
        Collections.sort(out);
        double[] r = new double[out.size()];
        for (int i = 0; i < r.length; i++) {
            r[i] = out.get(i);
        }
        return r;
    }

    private static long daysFromDate(String date) throws Exception {
        String[] d = date.split("[-/]");
        if (d.length < 3) {
            throw new Exception("日期格式应为 YYYY-MM-DD 或 YYYY/MM/DD");
        }
        return BeamPointingCore.ymdToDays(
                Integer.parseInt(d[0]), Integer.parseInt(d[1]), Integer.parseInt(d[2]));
    }

    /** 解析 "HH:MM" 或 "HH:MM:SS[.fff]" 为当日秒。 */
    private static double parseTOD(String time) throws Exception {
        String[] p = time.split(":");
        if (p.length < 2) {
            throw new Exception("时间格式应为 HH:MM 或 HH:MM:SS（得到：" + time + "）");
        }
        double h = Double.parseDouble(p[0]);
        double m = Double.parseDouble(p[1]);
        double s = (p.length > 2) ? Double.parseDouble(p[2]) : 0;
        return h * 3600 + m * 60 + s;
    }
}
