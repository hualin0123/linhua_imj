package com.linhua.plot;

import ij.IJ;
import ij.plugin.PlugIn;
import java.awt.Color;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * 多文件 Beam Pointing 对比：弹多选对话框选多个 CSV，按相对时间叠加各次测量的合成偏移轨迹与 X–Y
 * 位置云，并在 Log 输出跨 run 的 RMS/指向/位置统计。可选「对齐各 run 中心」、输入传播距离转 μrad。
 *
 * <p>渲染与 PNG 导出统一委托 {@link BeamPointingCompareRender}，本类只负责文件选择、解析与构建序列视图；
 * 分阶段对比见 {@link BeamPointingPhaseCompare_}。</p>
 */
public class BeamPointingCompare_ implements PlugIn {

    @Override
    public void run(String arg) {
        File[] filesArr = openChooser();
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

    /**
     * 默认直接进入「手动多选」窗口（鼠标点选，适合少量文件）；
     * 窗口左下角保留一个「文件夹 + 关键词」入口，文件很多时一键切到批量筛选模式。
     * 取消或未选中任何文件时返回空数组。
     */
    private File[] openChooser() {
        String desktop = new File(System.getProperty("user.home"), "Desktop").getPath();
        JFileChooser fc = new JFileChooser(desktop);
        fc.updateUI(); // 提前实例化 UI，便于在底部按钮条插入入口
        fc.setMultiSelectionEnabled(true);
        fc.setDialogTitle("选择 Beam Pointing CSV（可多选）");
        fc.setFileFilter(new FileNameExtensionFilter("*.csv", "csv"));

        JButton folderBtn = new JButton("文件夹 + 关键词…");
        folderBtn.setToolTipText("文件很多时，改用「文件夹 + 关键词」批量筛选");
        folderBtn.addActionListener(e -> {
            File[] ff = openFolderKeywordDialog(fc);
            if (ff != null && ff.length > 0) {
                folderChosenFiles = ff;
                fc.approveSelection(); // 用批量筛选结果直接结束选择
            }
        });
        addToChooserBottomLeft(fc, folderBtn);

        int r = fc.showOpenDialog(null);
        if (r == JFileChooser.APPROVE_OPTION) {
            if (folderChosenFiles != null) {
                File[] f = folderChosenFiles;
                folderChosenFiles = null;
                return f;
            }
            File[] sel = fc.getSelectedFiles();
            return sel == null ? new File[0] : sel;
        }
        return new File[0];
    }

    /** 暂存「文件夹 + 关键词」模式选中的文件（经 approveSelection 带回主流程）。 */
    private File[] folderChosenFiles;

    /**
     * 把组件塞进 JFileChooser 底部按钮条的最左侧（L&F 无关：定位含 Open/Cancel 按钮的那条面板）。
     * 若无法定位底部条，则退化为右侧 accessory，保证入口始终可用。
     */
    private static void addToChooserBottomLeft(JFileChooser fc, java.awt.Component comp) {
        JPanel p = findButtonPanel(fc);
        if (p != null) {
            p.add(comp, 0);
            p.revalidate();
            return;
        }
        JPanel box = new JPanel();
        box.add(comp);
        fc.setAccessory(box);
    }

    /** 递归查找含 ≥2 个 JButton 的容器（即 JFileChooser 底部按钮条）。 */
    private static JPanel findButtonPanel(java.awt.Container root) {
        for (java.awt.Component comp : root.getComponents()) {
            if (comp instanceof java.awt.Container) {
                java.awt.Container c = (java.awt.Container) comp;
                int btn = 0;
                for (java.awt.Component child : c.getComponents()) {
                    if (child instanceof JButton) {
                        btn++;
                    }
                }
                if (btn >= 2) {
                    return (JPanel) c;
                }
                JPanel deeper = findButtonPanel(c);
                if (deeper != null) {
                    return deeper;
                }
            }
        }
        return null;
    }

    /**
     * 弹出「文件夹 + 关键词」筛选对话框，返回选中的文件；取消或无匹配时返回 null。
     */
    private static File[] openFolderKeywordDialog(java.awt.Component parent) {
        String desktop = new File(System.getProperty("user.home"), "Desktop").getPath();
        JTextField folderField = new JTextField(22);
        folderField.setText(desktop); // 默认定位到桌面，与手动多选窗口一致
        JButton browse = new JButton("浏览...");
        JTextField keywordField = new JTextField(22);
        JCheckBox recursive = new JCheckBox("包含子文件夹", false);

        JPanel folderPanel = new JPanel();
        folderPanel.setLayout(new BoxLayout(folderPanel, BoxLayout.Y_AXIS));
        JPanel row1 = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT));
        row1.add(new JLabel("文件夹："));
        row1.add(folderField);
        row1.add(browse);
        JPanel row2 = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT));
        row2.add(new JLabel("关键词："));
        row2.add(keywordField);
        row2.add(new JLabel("（文件名包含，逗号/空格分隔多个，留空=全部）"));
        JPanel row3 = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT));
        row3.add(recursive);
        folderPanel.add(row1);
        folderPanel.add(row2);
        folderPanel.add(row3);

        browse.addActionListener(e -> {
            // 以文件夹输入框当前值（默认桌面）作为浏览起点，避免每次都从“文档”开始
            File start = new File(folderField.getText().trim());
            if (!start.isDirectory()) {
                start = new File(System.getProperty("user.home"), "Desktop");
            }
            JFileChooser fcd = new JFileChooser(start);
            fcd.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fcd.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
                folderField.setText(fcd.getSelectedFile().getAbsolutePath());
            }
        });

        int opt = JOptionPane.showConfirmDialog(parent, folderPanel,
                "文件夹 + 关键词筛选", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (opt != JOptionPane.OK_OPTION) {
            return null;
        }
        File folder = new File(folderField.getText().trim());
        if (!folder.isDirectory()) {
            IJ.error("文件夹无效", "请选择一个有效文件夹");
            return null;
        }
        File[] files = PlotUtil.chooseByFolder(folder, keywordField.getText(), recursive.isSelected(), "csv");
        if (files.length == 0) {
            IJ.error("没有匹配的文件", "该文件夹下没有符合关键词/扩展名的 CSV");
        }
        return files;
    }

    private void compare(List<File> files) throws Exception {
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
        List<BeamPointingCompareRender.SeriesView> views = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            List<BeamPointingCore.Sample> s = datas.get(i).samples;
            double[] st = BeamPointingCore.centroidStats(s);
            views.add(new BeamPointingCompareRender.SeriesView(
                    names.get(i), colors[i], s, st[0], st[1], st[2], st[3]));
        }
        BeamPointingCompareRender render = new BeamPointingCompareRender(views);
        File outDir = files.get(0).getParentFile();
        String base = files.get(0).getName();
        int di = base.lastIndexOf('.');
        render.setExport(outDir, di > 0 ? base.substring(0, di) : base);
        render.show(null);
    }
}
