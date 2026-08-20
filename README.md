# GTNH 合成树共享（Crafting Tree Sharing for GTNH）

把 GTNH（GregTech New Horizons，1.7.10）里 **NEI 书签写好的配方链/合成树**，导出成网页，供多人一起看材料、规划生产、勾选完成进度。

## 它做什么

- **游戏内导出**：一个纯客户端 Forge 模组，输入 `/treeexport` 把 NEI 书签里的配方链（含每个物品的精确数量）和物品图标导出成 JSON。
- **网页/服务器共享**：一个零依赖的 Node 服务器，接收每个玩家上传的树，按玩家名分页展示；网页上可勾选「已完成」，状态实时同步给所有人（SSE 推送）。
- **本地离线查看**：一个可双击打开的静态网页，无需服务器也能看自己的树。

## 结构

```
gtnh-tree/
├── mod/                # 游戏内导出模组（Forge 1.7.10 / GTNH）
│   ├── build.gradle.kts, gradle.properties, ...
│   └── src/main/java/gtnhtreeexport/
│       ├── GTNHTreeExport.java       # 模组入口，注册命令
│       ├── TreeExportCommand.java    # /treeexport 命令
│       ├── TreeConfigGui.java        # /treeexport config 设置界面
│       ├── ModConfig.java            # 配置持久化（服务器地址）
│       ├── IconExportScreen.java     # 游戏内渲染物品图标
│       ├── TreeUploader.java         # 上传到服务器
│       └── ClientTickHandler.java    # 延迟打开 GUI + 打印消息
├── server/             # 多人共享服务器（Node.js，零依赖）
│   ├── server.js       #   POST /api/upload、/api/check；GET /api/players、/api/tree、/api/checks、/api/events(SSE)
│   └── public/
│       ├── viewer.html            # 多玩家网页（标签页 + 勾选 + 下载模组）
│       └── gtnhtreeexport.jar     # 预编译 mod，供网页「下载模组」按钮下载
├── tools/              # 本地脚本
│   ├── bookmarks2tree.py  # 直接从 bookmarks.ini 生成树（零模组方案）
│   └── sync_export.py     # 把游戏导出同步到 web/ 供本地双击查看
└── web/                # 本地离线查看器（双击 viewer.html）
```

## 快速开始

### 1. 游戏内导出（模组）

1. 编译模组（见下文），把 `gtnhtreeexport-1.0.0.jar` 放进整合包 `.minecraft/mods/`。
2. 进游戏：
   - `/treeexport` → 导出当前书签的配方链 + 图标到 `.minecraft/dumps/`
   - `/treeexport config` → 填写共享服务器地址（如 `http://192.168.1.10:8080`）
   - `/treeexport` 在配置了服务器后会自动把树上传上去

> 模组每次导出前会自动把 NEI 内存里的最新书签刷到磁盘，无需退出世界。

### 2. 本地离线查看（无需服务器）

```bash
# 把游戏导出同步到 web/ 并生成内嵌数据
python3 tools/sync_export.py "/path/to/.minecraft/dumps" web
# 双击 web/viewer.html 即可查看
```

### 3. 多人共享（服务器）

```bash
cd server && node server.js   # 默认 8080 端口，可用 PORT 环境变量改
```

- 网页：`http://<服务器IP>:<端口>`
- 每个玩家上传后，网页顶部出现对应标签页，互不覆盖
- 勾选「已完成」会实时同步给所有访问者

## 编译模组

模组基于 [GTNewHorizons/ExampleMod1.7.10](https://github.com/GTNewHorizons/ExampleMod1.7.10) 的构建系统，需要 JDK 25 运行 Gradle（工具链自动下载）。

```bash
cd mod
./gradlew build        # 产物在 build/libs/gtnhtreeexport-1.0.0.jar
cp build/libs/gtnhtreeexport-1.0.0.jar ../server/public/gtnhtreeexport.jar   # 更新网页可下载的 jar
```

> 依赖：GTNH 版 NotEnoughItems（`com.github.GTNewHorizons:NotEnoughItems`，见 `dependencies.gradle`）。

## 常见问题

- **为什么之前要退出世界才能导出新书签？** 旧版本有这个问题，现已修复：导出前会调用 NEI 的 `bookmarkPanel.save()` 把内存里最新书签刷盘。
- **端口**：服务器用冷门端口更安全（如 45213），别用 80/443。
- **域名多服务器**：DNS 只映射域名→IP，无法按端口分流到不同机器；多台机器请用子域名。

## 许可

MIT License（见 LICENSE）。
