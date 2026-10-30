# LinHua —— ImageJ 工具集合插件

基于 Maven 的 ImageJ1 插件组。顶层菜单为 `Plugins>LinHua`，每个工具放在各自的子目录下。

## 目录结构

```
linhua_imj/
├── pom.xml                      # 依赖 net.imagej:ij 1.53t，Java 8
├── deploy.ps1                   # 一键部署脚本（见下方「构建与部署」）
├── run.bat / run.sh             # 本地编译并运行（需 Maven）
└── src/main/
    ├── java/com/linhua/
    │   ├── plot/                # 画图模块：通用自绘图表库 + 各 Plot 类功能
    │   │   ├── Plot.java          #   通用画图库（复原/缩放/平移/导出 PNG·SVG，matplotlib 风格）
    │   │   ├── SvgGraphics.java    #   Graphics2D -> SVG 桥接（Plot 内部使用）
    │   │   ├── PlotUtil.java       #   配色/标记分配、刻度数字格式、统一风格应用、统一文件选择（多选对话框）
    │   │   ├── BeamPointing_.java  #   功能：Beam Pointing 单次测量（X–Y 散点标 RMS 圈；右上角可输入传播距离转 μrad）
    │   │   ├── BeamPointingCompare_.java #   功能：Beam Pointing 多文件对比（多选对话框，按相对时间叠加，图表右上角可切换对齐中心、输入传播距离将 X/Y 转 μrad；散点半透明异形，每 run 标 RMS 圈）
    │   │   ├── BeamPointingCore.java #   共用：CSV 解析 + 数据载体（单跑与对比共用）
    │   │   ├── M2_.java            #   功能：M2 光束质量因子
    │   │   ├── Multiline1_.java    #   功能：多线图（同 X，多选对话框）
    │   │   ├── Multiline2_.java    #   功能：多线图（异 X，多选对话框）
    │   │   ├── Multifitting1_.java #   功能：多组拟合（同 X，gaussian/sech2/small_G）
    │   │   ├── Multifitting2_.java #   功能：多组拟合（异 X）
    │   │   ├── OutputPower_.java   #   功能：输出功率曲线
    │   │   ├── PulseDuration_.java #   功能：脉冲时长（归一化轨迹）
    │   │   ├── PulseDuration1_.java#   功能：脉冲时长（含 gaussian/sech2 拟合 + FWHM）
    │   │   ├── PowerStability_.java#   功能：功率稳定性（RMS 抖动标注）
    │   │   ├── Spectrum_.java      #   功能：光谱（min-max 归一化）
    │   │   ├── Absorption_.java    #   功能：吸收效率/吸收功率密度 + 写回 x4、π偏+σ偏
    │   │   └── Pabs_lnG_.java      #   功能：吸收截面/荧光寿命拟合 + 写回 Pabs/G/lnG
    │   ├── fit/                  # 曲线拟合
    │   │   ├── CurveFitter.java    #   通用 Levenberg–Marquardt 非线性最小二乘
    │   │   └── Models.java         #   gaussian / sech2 / small_G / Pabs_lnG 模型
    │   └── io/                   # 文件读写（纯 JDK，无第三方依赖）
    │       ├── XlsxReader.java     #   极简 xlsx 读取器
    │       ├── XlsxWriter.java     #   极简 xlsx 写出器（写回计算列）
    │       └── Table.java          #   XlsxReader 结果的列访问封装
    │   └── <yourtool>/          # 今后新增工具：在此加子目录
    │       └── Xxx_.java
    └── resources/
        └── plugins.config        # 菜单清单（Plugins>LinHua > ...）
    ```

    ## 作图插件 UI 规范（Plot 风格）

    所有基于 `plot/Plot.java` 自绘图表的插件，应遵循统一的界面与作图约定，保证多工具之间视觉一致、专业。
    以下规范来自 `BeamPointing_` / `BeamPointingCompare_` 的实际实现，新作图插件请直接参考复用。

    ### 1. 图标工具栏（主工具栏）

    - **固定四个图标按钮**，顺序为：`复原(Home)` → `缩放(Zoom)` → `平移(Pan)` → 〔分隔线〕 → `存储图形(Save)`。
    - 图标全部用**代码矢量绘制**（见 `Plot.makeIcon` + `drawHome/drawZoom/drawPan/drawSave`），不放图片文件；四个按钮等大同高，外观统一。
    - 每个按钮必须配 `setToolTipText(...)`。
    - 工具栏 `setFloatable(false)`；`Zoom/Pan` 这类开关按钮用 `Plot.setButtonSelected(b, sel)` 高亮激活态（背景 `#CFE8FF`）。
    - 仿照 `Plot.buildToolBar()` 的实现即可。

    ### 2. 选项控件放「第二条工具条」，不要挤进图标栏

    - 可交互选项（如 `对齐中心` 复选框、`传播距离` 输入框）应放在**图标工具栏下方的独立一行**（`Plot.addBottomControl`，内部用 glue 右对齐），而不是塞进图标栏。
    - 这样图标行高度不被撑高，两条工具条都是整齐单行，视觉分层清晰。

    ### 3. 选项组样式（`PlotUtil.buildOptionsPanel`）

    - 选项整体包在一个**单行 `JPanel`** 里，外面加一圈**灰色实线边框**（`LineBorder #999999`）+ 内边距，像一个有意为之的"控制组"，呼应左侧图标按钮的灰色边框。
    - 子选项之间用一条 **1px 灰色竖直分隔线**（`JSeparator`，`foreground #999999`）隔开（如 `对齐中心 | 传播距离`）。
      - ⚠️ Metal 风格下 `JSeparator` 默认宽度远大于 1px，必须强制 `setPreferredSize(1,1)` + `setMaximumSize(1, Integer.MAX_VALUE)`，否则会凭空多出空白。
    - 间距紧凑对称：元素间用 `Box.createHorizontalStrut(4)`。
    - **数值输入框**：宽度用**固定像素**锁死（`minimum/preferred/maximum` 三个 size 都设为同一个 `Dimension`，例如 50px），文字**右对齐**（`JTextField.RIGHT`）。
      - 不要用 `setColumns` 做百分比/列数换算去"凑宽度"，直接定像素最可控。

    ### 4. 坐标轴标题

    - 格式：`Offset X / urad` 或 `Offset X / um`——**单位直接写在标题里**。
    - 不要额外追加冗余词（如 `(pointing)`）；标题尽量短。

    ### 5. 配色

    - 多曲线/多 run 用 `PlotUtil.viridis(t)` 色表区分（暗紫 → 蓝绿 → 黄），颜色/标记分配走 `PlotUtil` 的共用辅助方法，不要各自硬编码。

    ### 6. 指向(Beam Pointing)类指标口径（领域标准，供同类插件沿用）

    - **标准孔径指向公式**：`pointing[urad] = 空间RMS[μm] × 1000 / L[mm]`，即 μm→μrad 换算系数为 `1000/L`（L 单位 mm）。
    - `mean offset` = 质心偏移 `hypot(meanX, meanY)`（单位 μm）。
    - **单跑 RMS 圈以质心为中心**（不是原点）。
    - 结果用 **ImageJ 标准 `ResultsTable`** 展示：数值列在前、`File` 文字列在后（ResultsTable 惯例），数值列右对齐，支持 `Save As CSV`。

    > 以上约定若后续调整，请同步更新本节，保持"规范"与代码一致。

    ## 新增一个工具

1. 在 `src/main/java/com/linhua/<yourtool>/` 下新建命令类，类名以 `_` 结尾、实现 `ij.plugin.PlugIn`，
   例如 `com.linhua.measure.Measure_.java`。
2. 在 `src/main/resources/plugins.config` 加一行：
   - 与现有命令**同级**（直接挂在 LinHua 下）：
     ```
     Plugins>LinHua, "显示名", com.linhua.<yourtool>.Xxx_
     ```
   - 或归到 LinHua 下的**子菜单**（自动生成子菜单）：
     ```
     Plugins>LinHua><yourtool>, "显示名", com.linhua.<yourtool>.Xxx_
     ```

> ⚠️ **菜单分隔符 `>` 两侧不要加空格！** ImageJ 用 `startsWith("Plugins>")` 这类判断识别子菜单，
> 写成 `Plugins > LinHua`（带空格）会被当成普通菜单名，结果工具直接挂在 `Plugins` 顶级、而 `LinHua`
> 子菜单根本不生成。正确写法一律是 **`Plugins>LinHua`（无空格）**，多级子菜单同理：`Plugins>LinHua><yourtool>`。
> 这是本项目踩过的真实坑，务必照此书写。

## 构建与部署（标准方法）

> 以后说「部署」即按此流程执行：编译整个源码树 → 打 jar → 复制到 ImageJ 的 plugins 目录。

**关键路径（固定）**
- ImageJ 根目录：`C:\Users\hual\ij154-win-java8\ImageJ`
- `ij.jar`：`C:\Users\hual\ij154-win-java8\ImageJ\ij.jar`
- 部署目标：`C:\Users\hual\ij154-win-java8\ImageJ\plugins`
- 编译用 JDK：`C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot`（含 `javac` / `jar`）

**步骤（无需联网，直接对本地 ij.jar 编译）**
1. 用本机 `javac` 以 `--release 8` 递归编译 `src/main/java` 下的全部 `.java`，
   classpath 指向本地 `ij.jar`。
2. 把 `src/main/resources/plugins.config` 拷进编译输出目录（jar 根目录需要它来生成菜单）。
3. 用 `jar` 打成 `LinHua_VyyMMdd.jar`（日期戳为当日 `yyMMdd`）。
4. 将 jar 复制到 `C:\Users\hual\ij154-win-java8\ImageJ\plugins`。
5. 若 ImageJ 已在运行，重启一次以加载新 jar；菜单 `Plugins>LinHua > ...` 即出现。

**一键脚本**
在 `linhua_imj` 目录下执行：

```powershell
powershell -ExecutionPolicy Bypass -File deploy.ps1
```

脚本会自动完成上述 1–4 步（使用独立干净目录 `target\build`，避免混入旧 class），
并把 jar 覆盖部署到 plugins 目录。

## 本地运行（可选）

```bat
run.bat        # Windows（需 Maven + 联网拉取依赖）
./run.sh       # Linux / macOS
```

生成的 jar 位于 `target/LinHua_VyyMMdd.jar`，放入 ImageJ/Fiji 的 `plugins/` 目录即插即用。
