# LinHua —— ImageJ 工具集合插件

基于 Maven 的 ImageJ1 插件组。顶层菜单为 `Plugins > linhua`，每个工具放在各自的子目录下。

## 目录结构

```
linhua_imj/
├── pom.xml                      # 依赖 net.imagej:ij 1.53t，Java 8
├── deploy.ps1                   # 一键部署脚本（见下方「构建与部署」）
├── run.bat / run.sh             # 本地编译并运行（需 Maven）
└── src/main/
    ├── java/com/linhua/
    │   ├── helloworld/          # 示例工具：Hello World
    │   │   └── HelloWorld_.java  #   命令类（实现 ij.plugin.PlugIn）
    │   └── <yourtool>/          # 今后新增工具：在此加子目录
    │       └── Xxx_.java
    └── resources/
        └── plugins.config        # 菜单清单（Plugins > linhua > ...）
```

## 新增一个工具

1. 在 `src/main/java/com/linhua/<yourtool>/` 下新建命令类，类名以 `_` 结尾、实现 `ij.plugin.PlugIn`，
   例如 `com.linhua.measure.Measure_.java`。
2. 在 `src/main/resources/plugins.config` 加一行：
   ```
   Plugins > linhua > <yourtool>, "Label", com.linhua.<yourtool>.Xxx_
   ```
   （同一父菜单 `linhua` 下的多个工具会自动归到同一个子菜单。）

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
5. 若 ImageJ 已在运行，重启一次以加载新 jar；菜单 `Plugins > linhua > ...` 即出现。

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
