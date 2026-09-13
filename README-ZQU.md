# Where To Study · 肇庆学院魔改版

本项目基于上游 [Nemoyuzx/where_to_study](https://github.com/Nemoyuzx/where_to_study) v0.2.9 魔改而成，
对接**肇庆学院教务系统（乘方教务）**，并**仅保留 Android 客户端的"查看个人课表"功能**。
北邮专属功能未删除，只做了注释停用，代码中所有改动均以 `魔改（肇庆学院）` 注释标记，便于日后对照或恢复。

## 功能范围

| 状态 | 功能 |
| --- | --- |
| ✅ 保留 | 个人课表查看（逐周抓取全部 25 个教学周、本地缓存、周次切换） |
| ✅ 保留 | 肇庆学院 14 节制作息时间轴、颜色主题、中英双语 |
| ✅ 保留 | 学期参数（学期号 / 开学日期）设置 |
| ⏸️ 停用 | 空教室查询、每日课程提醒、班车/DDL 信息查询、桌面小组件、教学日历、已删除课程管理、教学云平台密码、多校区选择（以上均为北邮专属） |

## 环境要求

- Android 7.0（API 24）及以上
- 手机能访问教务系统：校园网直连，或校外通过 WebVPN

## 安装（方式一：直接安装 APK）

1. 到本仓库 [Releases](../../releases) 页面下载 `where-to-study-zqu-debug.apk`；
2. 传输到手机（微信文件传输助手、数据线均可）后点击安装；
3. 首次安装需允许"安装未知应用"（系统设置 → 应用 → 浏览器/文件管理器 → 允许安装未知应用）；
4. 打开 App，进入底部"设置"完成首次配置（见下文）。

## 安装（方式二：自行编译）

环境要求：

- JDK 17 或更高（实测 JDK 21 可用）
- Android SDK：Platform 36（android-36）、Build-Tools 35.0.0、Platform-Tools
- Gradle 8.14.5（项目 wrapper 自带，无需单独安装）

步骤：

```powershell
cd native\android
# 若本机未配置 ANDROID_HOME，先创建 local.properties，写入：
#   sdk.dir=D:/Android/Sdk   （改成你的 SDK 实际路径）
.\gradlew.bat assembleDebug
# 产物位置：app\build\outputs\apk\debug\app-debug.apk
```

构建环境注意事项：

- **网络有透明 TLS 拦截时**（常见于杀毒软件 / 企业网络，症状为依赖下载报
  `PKIX path building failed`）：项目的 `gradle.properties` 已内置
  `-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT`，让 Gradle 信任 Windows 系统证书库，一般可直接构建。
- **Gradle 发行版下载过慢时**：可手动从镜像（如腾讯云 `https://mirrors.cloud.tencent.com/gradle/gradle-8.14.5-bin.zip`）
  下载 `gradle-8.14.5-bin.zip`，解压到
  `%USERPROFILE%\.gradle\wrapper\dists\gradle-8.14.5-bin\<哈希目录>\gradle-8.14.5\`，
  并在同目录放置空的 `gradle-8.14.5-bin.zip.ok` 标记文件，wrapper 会跳过下载。

## 首次配置

打开 App → 底部"设置"→"个人账户"，填写四项：

| 字段 | 说明 | 示例 |
| --- | --- | --- |
| 学号 | 教务系统学号（作为本机账户标识） | `2026xxxxxx` |
| 教务 Cookie | 浏览器登录教务后复制的**完整 Cookie 请求头**（见下方获取方法） | `JSESSIONID=...`（一长串） |
| 教务系统地址 | 校内网**留空即可**（默认 `https://jwgl.zqu.edu.cn`）；校外填 WebVPN 地址 | 见下文 |
| 班级代码（bjdm） | 课表按班级拉取，默认已填法学5班 `114334763` | `114334763` |

填好后依次点 **"保存"** 和 **"获取"**，回到首页即可看到课表。

### 如何获取教务 Cookie

1. 电脑浏览器登录教务系统；
2. 按 `F12` 打开开发者工具 → 切到 **Network（网络）** 标签 → 刷新页面；
3. 点击任意一条 `jwgl` 开头的请求 → 在 **Request Headers（请求标头）** 中找到 `Cookie:`；
4. 复制冒号后面的**完整内容**，粘贴到 App 的"教务 Cookie"输入框。

### Cookie 有效期

教务 Cookie 会过期（短则几小时，长则数天，取决于教务系统策略）。当课表拉取失败或内容变空时，
重新登录教务系统并按上面方法换一份新 Cookie，保存后再次"获取"即可。

### 校外使用（WebVPN）

校外网络无法直连教务时，把"教务系统地址"换成 WebVPN 前缀地址：

```
https://webvpn-free.zqu.edu.cn/https/77726476706e69737468656265737421fae04690692a7945300d8db9d6562d
```

> 该前缀来自 WebVPN 登录后的教务页面地址，若学校调整了 WebVPN 编码，请以浏览器实际地址为准。

## 使用说明

- 首页即课表页，可左右切换周次；课表一次性抓取 1~25 周（遇到连续 2 个空周自动停止）；
- **学期信息**（设置 → 学期信息）：学期号格式 `YYYY-YYYY-S`，默认 `2026-2027-1`，开学日期默认 `2026-08-31`；
  下学期时把学期号改为 `2027-2028-1` 等即可（App 会自动换算成接口参数，如 `202601` → 下学期 `20271`）；
- 班级代码查询方法：浏览器打开教务课表页时，地址栏 URL 中的 `bjdm=` 参数就是本班代码。

## 工作原理

- 数据接口：`GET {教务地址}/xsbjkbcx!xskbList2.action?xnxqdm={学期代码}&bjdm={班级代码}&zc={周次}`，
  与教务网页课表页同源；按周解析 HTML 中的课程行（节次来自 `jcdm`，如 `030405` 表示第 3、4、5 节）；
- 作息时间：14 节制 —— 第 1 节 08:00 起（每节 40 分钟），第 6 节 14:30 起，第 11 节 19:00 起，第 14 节 21:30~22:10；
- 学期起始日由课表数据的 `pkrq`（排课日期）反推，也可在设置中手动指定。

## 隐私说明

- App 不含任何统计、埋点或数据上传代码，课表与配置全部保存在本机；
- 学号与 Cookie 通过 **AndroidKeyStore 加密**后存储，不落明文；
- 更换学号保存时会自动清空本地课表缓存，避免展示他人课表。

## 与上游的关系与致谢

- 上游项目：[Nemoyuzx/where_to_study](https://github.com/Nemoyuzx/where_to_study)（北邮空教室与个人课表联动查询应用）；
- 本 fork 仅魔改 Android 端（`native/android`），其余平台代码未改动；
- 感谢上游作者 [Nemoyuzx](https://github.com/Nemoyuzx) 开源，欢迎给上游点 ⭐。

## 许可证

继承上游项目许可证，见 [LICENSE](./LICENSE)。
