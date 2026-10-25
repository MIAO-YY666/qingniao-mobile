# 青鸟·口袋版（qingniao-mobile）

> 手机端的青鸟：本地小模型 + 无障碍读屏操作 + 联网搜索 + 截图汇报。
> 与 PC 端 [qingniao](https://github.com/MIAO-YY6/qingniao) 同人格、同记忆格式。

## 功能

- **本地大脑**：llama.cpp 跑 GGUF 量化模型（默认推荐 Qwen3-1.7B Q4），可选云端 DeepSeek 模式
- **读屏与操作**：无障碍 UI 树读取 + 点击/滑动/输入/打开 App（JSON 动作协议 Agent 循环）
- **联网搜索**：Bing/百度兜底，搜到的资料自动进上下文
- **截图汇报**：任务完成自动截屏、QQ 分享发送、发完即删
- **App 用法学习**：每个 App 一份经验手册，越用越顺手
- **人向记忆**：与 PC 端同构的 people json 文件，双向拷贝互通
- **新手向导**：检测手机配置 → 推荐最优模型 → 一键下载自动配置 → 权限教学

## 构建

```bash
bash tools/fetch-llamacpp.sh   # 拉取 llama.cpp 源码（一次性）
# 用 Android Studio 打开本目录，或命令行：
tools/gradle-build.bat         # 需自备 JDK17+/Android SDK/NDK/CMake
```

模型下载（hf-mirror，国内直连）：`Qwen3-1.7B-GGUF` 的 Q4_K_M 文件；App 内新手向导可一键下载。

## 声明

- QQ 自动化依赖无障碍服务，仅供个人自用；请遵守各 App 用户协议
- 本项目不收集任何数据；所有记忆/日志仅存于本机

## License

MIT
