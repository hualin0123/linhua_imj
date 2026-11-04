# LinHua —— ImageJ 工具集合插件

基于 Maven 的 ImageJ1 插件组。顶层菜单为 `Plugins>LinHua`，下设 `Plot`（作图 / 激光测量）与
`Gaus`（高斯光束 / ABCD 光路计算）两个子菜单，每个工具放在各自的子目录下。

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
    │   │   ├── BeamPointingCompare_.java #   功能：Beam Pointing 多文件对比（多选对话框，按相对时间叠加，可切换对齐中心、输入传播距离将 X/Y 转 μrad；散点半透明异形、每 run 标 RMS 圈；散点图图例附带 RMS 抖动值，单位随坐标轴动态显示 um/urad）
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
    │   ├── gaus/                # 高斯光束 / ABCD 矩阵光路（菜单 Plugins>LinHua>Gaus，详见「Gaus 模块」）
    │   │   ├── GausCore.java      #   纯 Java 计算内核：复 q 参数、ABCD 元件/级联、稳定性判据、腰斑/曲率反解
    │   │   ├── GausUtil.java      #   稳定区间、求根等共用工具
    │   │   ├── abcd1~5 / abcdx / abcd_compose      #   各腔型 ABCD 矩阵扫描与组合（腰斑 w0、稳定区、焦距）
    │   │   ├── collimate / propagate / propagate1 / transform   #   准直、自由传播、透镜变换
    │   │   ├── lens_eq / lens_eq1 / lens_eq2      #   等效透镜/反射镜求解 Z1、Z2
    │   │   ├── matching / matching1               #   模匹配四解（含随焦距 F 的变化）
    │   │   └── multipass / multipass1             #   多程腔：单程矩阵 N 次幂逐程解 q
    │   ├── io/                   # 文件读写（纯 JDK，无第三方依赖）
    │   │   ├── XlsxReader.java     #   极简 xlsx 读取器
    │   │   ├── XlsxWriter.java     #   极简 xlsx 写出器（写回计算列）
    │   │   └── Table.java          #   XlsxReader 结果的列访问封装
    │   └── <yourtool>/          # 今后新增工具：在此加子目录
    │       └── Xxx_.java
    └── resources/
        └── plugins.config        # 菜单清单（Plugins>LinHua>Plot、Plugins>LinHua>Gaus）
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

### 7. 图例标注 RMS 抖动值（随单位动态显示）

- **指向类散点图**的每个 run 图例文字格式为 `文件名 (数值 单位)`，例如 `run01 (3.4 um)` / `run01 (12.3 urad)`，
  可直接从图上读出该 run 的 RMS 抖动，无需再对照结果表。
- **数值与单位必须跟随当前换算系数 `factor`，不要写死 `urad`**：
  - 未输入传播距离（`factor = 1`，μm 模式）：显示 `crms`（绕质心的空间 RMS 半径），单位 `um`；
  - 输入传播距离（`factor = 1000 / L[mm]`，μrad 模式）：显示 `crms × factor`，单位 `urad`，此时才是真正的指向角。
- 该数值**与同色 RMS 圈半径完全一致**（圈半径同为 `crms × factor`），也与 `ResultsTable` 的 `Pointing/urad` / `RMS/um` 列一致。
- 图例文字每次由 `renderScatter` 重新生成（`clearSeriesData` → `addScatter(label)` → `refresh`），
  切换「对齐中心」或修改传播距离时自动刷新；**不要在初始化时把数值写死**。
- 只加在 X–Y 散点图；**时间轨迹图恒为 μm**，加 μrad 后缀会与坐标轴矛盾。
- 参考实现：`BeamPointingCompare_.renderScatter`（`String.format("%.1f %s", crms[i]*factor, unit)`）。

> 以上约定若后续调整，请同步更新本节，保持"规范"与代码一致。

## Gaus 模块（高斯光束 / ABCD 光路）

菜单 `Plugins>LinHua>Gaus`，共 18 个命令，由同名 Python 脚本（`.py`）移植而来；类名沿用脚本名
（小写、无 `_` 后缀），全部实现 `ij.plugin.PlugIn`。输入用 ImageJ `GenericDialog`，
作图复用 `plot/Plot.java` + `PlotUtil`，因此同样遵循上方「作图插件 UI 规范」。

- **单位约定**：几何长度、焦距、曲率半径一律 **mm**；波长在 GUI 中以 **nm** 输入，内部乘 `1e-6` 转 mm
  （默认 2060 nm = 0.00206 mm）。
- **分层**：`GausCore.java` 是纯 Java 计算内核（不依赖第三方库）：轻量复数 `Cx`、ABCD 元件矩阵与级联、
  环形矩阵 `(A+D)/2` 稳定性判据、基模腰斑 `w0 = sqrt(2λ|B|/(π√(4-(A+D)²)))`、复 q 参数
  `q2=(A·q1+B)/(C·q1+D)` 及其腰斑/曲率反解；`GausUtil.java` 提供稳定区间、求根等共用工具；其余为命令类。
- **命令分组**：
  - `abcd1`–`abcd5` / `abcdx` / `abcd_compose`：各腔型 ABCD 矩阵扫描（腰斑 w0、稳定区间、焦距），
    `abcd_compose` 为可交互组合矩阵；
  - `propagate` / `propagate1` / `collimate` / `transform`：自由空间传播、准直、透镜变换；
  - `lens_eq` / `lens_eq1` / `lens_eq2`：等效透镜/反射镜求解 Z1、Z2；
  - `matching` / `matching1`：模匹配四解（含随焦距 F 的变化曲线）；
  - `multipass` / `multipass1`：多程腔（单程矩阵 N 次幂，逐程解 q）。
- 新增命令时记得在 `plugins.config` 加一行，格式见下节。

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

> 说明：类名以 `_` 结尾是 ImageJ 在 `plugins/` 目录下**自动发现**插件的约定；只要在 `plugins.config` 中
> **显式注册**，不带 `_` 的类同样可用。现有 `gaus/` 的 18 个命令即属此情况。新工具仍建议遵循 `_`
> 命名以保持风格一致。

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
5. 若 ImageJ 已在运行，重启一次以加载新 jar；菜单 `Plugins>LinHua>Plot` / `Plugins>LinHua>Gaus` 即出现。

**一键脚本**
在 `linhua_imj` 目录下执行：

```powershell
powershell -ExecutionPolicy Bypass -File deploy.ps1
```

脚本会自动完成上述 1–4 步（使用独立干净目录 `target\build`，避免混入旧 class），
并把 jar 覆盖部署到 plugins 目录。

> ⚠️ **deploy.ps1 脚本踩过的坑（务必照此维护，否则要么脚本直接解析失败，要么部署出的 jar 是空的）**：
> 这些经验来自一次真实事故——`deploy.ps1` 跑完显示「Deployed」成功，但 ImageJ 启动却报
> `ClassNotFoundException: com.linhua.plot.BeamPointingCompare_`，排查发现 jar 里**根本没有 `.class` 文件**。根因全部出在
> `powershell -File deploy.ps1` 这种非交互运行方式上，已固化为脚本内的防御代码，改动时请勿回头：
> 1. **注释只用 ASCII（英文）**：PowerShell 按系统 ANSI 代码页解析 `.ps1`，一旦写进中文等非 ASCII 注释，
>    UTF-8 字节会被错位解释，偶尔某个字节被当成字符串终结符 `"`，导致整脚本
>    `The string is missing the terminator` 解析失败。原始脚本因此全程用英文注释。若必须写中文，文件须存为
>    **UTF-8 with BOM**，否则照样乱码。
> 2. **`javac` 的输出必须走管道排出**：非交互 `-File` 下，若 javac 的 stdout/stderr 不被消费，它会
>    **静默 `exit 0` 却不产出任何 `.class`**，最终打成空 jar。调用须写成
>    `& $javac ... 2>&1 | Out-String`（或 `| Out-Null`），**不要裸调用** `& $javac ...`。
> 3. **源码清单用 javac `@文件` 传入**：非交互 `-File` 上下文中，把 PowerShell 数组直接展开给 `javac`
>    （即 `& javac ... $srcFiles`）会丢参数；应把源路径逐行写入一个临时 `@文件`，再以 `@清单` 传参。
>    写入用 `[IO.File]::WriteAllLines`（确保落盘），不要在写完后立刻让 javac 读尚未刷新的文件。
> 4. **脚本目录用 `$PSCommandPath` 或 `$MyInvocation.MyCommand.Path` 解析**，比 `$PSScriptRoot` 在
>    `-File` 下更可靠；并加回退，避免目录解析为空导致后续所有路径失效。
> 5. **打包前校验 class 数量**：编译后检查 `target\build` 下 `.class` 个数，为 0 直接中止，
>    这是上述坑的最后一道兜底防线，防止默默打包空目录。

## 本地运行（可选）

```bat
run.bat        # Windows（需 Maven + 联网拉取依赖）
./run.sh       # Linux / macOS
```

生成的 jar 位于 `target/LinHua_VyyMMdd.jar`，放入 ImageJ/Fiji 的 `plugins/` 目录即插即用。
