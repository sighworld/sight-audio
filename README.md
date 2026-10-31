# sight-audio

把**电脑上某个软件正在放的声音**分享给 Minecraft 里附近的人。走 Simple Voice Chat 的空间音频通道 ——
走近听得见，走远听不见，和你说话的声音享有完全一样的位置衰减。

**不需要虚拟声卡，不需要改系统设置。** 装完 mod，游戏里按 F9 点一下就行。

> ⚠️ **仅支持 Windows。** 抓声音走的是 WASAPI process loopback —— Windows 独有的系统接口。
> Linux / macOS 上 mod 能正常加载（`Com` 初始化会静默退化），但面板里不会有可选的程序。

---

## 玩家要装什么

| # | 东西 | 说明 |
|---|---|---|
| 1 | Fabric Loader | 装 mod 的前置（26.2 对应版本；**0.18.4 及以上都行**，不用非得最新） |
| 2 | Fabric API | SVC 依赖它，我们也用 |
| 3 | Simple Voice Chat | `voicechat-fabric-2.6.26.jar` |
| 4 | **sight-audio**（本 mod） | `sight-audio-0.1.0.jar` |

四个 jar 丢进 `mods/`，完事。**没有驱动，没有虚拟声卡，没有系统设置。**

---

## 怎么用

| 键 | 作用 |
|---|---|
| **F9** | 打开 / 关闭分享面板 |
| **F10** | 快速开关（不开面板，直接用上次选的那个） |

面板里：左边是「正在出声的程序」列表（3 秒自动刷新），**单击选中、双击直接开始**；
下面是音量滑块，拖一下立刻生效；底部「开始分享 / 停止分享」按钮。

列表第一行是 **「整个系统（不含本游戏）」**：不想挑具体程序、想把电脑上除了游戏以外
的所有声音都分享出去时用它（游戏自己的音效和其他玩家的语音会被排除，不会互相回授）。

### 命令（都可以不打，面板里都能做）

> 根命令故意只用两个字母 `sa`。Fabric 的**客户端**命令在本地先匹配，
> 一旦注册 `/sightaudio`，服务端同名的命令就再也打不开了（连 OP 也不行）。

| 命令 | 作用 |
|---|---|
| `/sa` | 打开面板 |
| `/sa list` | 在聊天栏列一份可点击的列表（不开面板的快捷方式） |
| `/sa pick <编号>` | 配合上面的列表用 |
| `/sa system` | 分享整个系统（不含本游戏） |
| `/sa off` | 停止 |
| `/sa status` | 当前在采谁、缓冲多少、上次报错 |
| `/sa vol <0-300>` | 音量百分比，默认 100 |
| `/sa buf <1-50>` | 抖动缓冲帧数，1 帧 = 20 ms，默认 5（100 ms） |
| `/sa device <名字>` | 后备方案：改用录音设备（虚拟声卡） |

配置落在 `config/sight-audio.json`。

---

## 几个真实会遇到的限制

**1. 目标程序以管理员身份运行时抓不到**
Windows 的进程环回要求权限不低于目标进程。如果 QQ 音乐之类是「以管理员身份运行」的，
要么把游戏也用管理员启动，要么让它以普通权限运行。报错会写 `拒绝访问`。

**2. DRM 保护的音源可能没有声音**
部分流媒体会在输出前加密，环回拿到的是静音。网易云/QQ 音乐的正常播放不受影响。

**3. 切换默认播放设备会断流**
插拔耳机、切蓝牙之后，Windows 会重建音频流。这时重新按一次 F9 即可。

**4. 采样率固定 48 kHz**
Windows 的进程环回只提供 48 kHz / 16 bit / 立体声，这是系统接口的硬约束，不是我们选的。
其他采样率的音源由系统重采样。

**5. 只抓自己这台机器**
别人听到的是你电脑上放的声音，不是从服务器下发的。所以听众不需要装曲库，也不需要下载。

---

## 自己听得到吗

你在自己电脑上放 QQ 音乐，本地扬声器本来就在响，所以你自己当然听得到。
mod 只负责把同一份声音**再送一份给附近的人**，不会再从游戏里播一遍（那会变成回声）。

---

## 和"占不占麦克风"的关系

不占。注入走的是 SVC 的 `MergeClientSoundEvent#mergeAudio()` —— 往麦克风流里**混**一层，
不是替换。放歌的时候照常说话，两边都会传出去。

SVC 那边的连带效果：

- 距离衰减、群组、单人音量、屏蔽某人 —— **全部自动生效**，因为对 SVC 来说这跟你说话没区别；
- 「正在说话」的绿喇叭图标**可能不亮**（那是按麦克风能量判断的），但声音是真的发出去了。

---

## 实现原理

```
目标进程 ──WASAPI process loopback──▶ 48k/16bit/2ch PCM
                                        │ 解交织取平均
                                        ▼
                                   单声道 960 点帧（20 ms）
                                        │ 抖动缓冲
                                        ▼
                             SVC mergeAudio() → Opus → 附近的人
```

- **抓取**：`ActivateAudioInterfaceAsync("VAD\Process_Loopback", PROCESS_LOOPBACK, pid)`，
  纯 JDK 25 的 `java.lang.foreign` 直调 COM，无 JNA、无 DLL、无外部依赖。
- **枚举**：`IAudioSessionManager2 → GetSessionEnumerator → IAudioSessionControl2::GetProcessId`，
  就是系统音量合成器那一列程序名的来源。
- **注入**：`MergeClientSoundEvent#mergeAudio(short[])`，每 20 ms 一次 960 点。

### 踩过的坑（改代码前先看）

1. `CoInitializeEx` **每个线程都要调**，漏了就是 `0x800401F0`。
2. `QueryInterface` 必须走**对象自己**的虚表 0 号槽，用别的接口的虚表调会静默失败（表现为 pid 永远 -1）。
3. upcall 拿到的指针 `byteSize = 0`，写之前必须 `reinterpret`。
4. 自己实现的 COM 回调，`QueryInterface` 必须**回自己**，回 `E_NOINTERFACE` 会得到 `0x8000000E`。
5. `Initialize` 只认 `48 kHz / 16 bit / 2 ch` + `AUDCLNT_STREAMFLAGS_LOOPBACK`。
6. 静音包（flag `0x2`）的数据指针**无效**，不能读，只能自己填 0。
7. 26.2 起 Mojang 取消混淆：不能写 `officialMojangMappings()`，但 `mappings` 又不能空 ⇒ 用自制恒等映射 jar。
8. `mergeAudio` 的调用点在 `MicThread.sendAudio()` 里，而「是否在说话」的判定在它之前。
   **但没关系**：混音列表由我们创建，null 的麦克风音频会被跳过，返回的只剩我们的声音 ——
   所以语音激活模式下不说话也能把歌传出去。

---

## 构建

### 前置

- **JDK 25**（`build.gradle` 的 toolchain 写死 25）
- 一个 **SVC 服务端 jar**（`voicechat-bukkit-2.6.26.jar`）—— 只用来抽 API 包，见下

### 通用步骤（任何机器）

```bash
# 1) 抽出 SVC 的纯 API 包，生成 libs/voicechat-api.jar（只需做一次）
python tools/extract-voicechat-api.py /path/to/voicechat-bukkit-2.6.26.jar

# 2) 构建
./gradlew build          # Windows 用 gradlew.bat build
# 产物：build/libs/sight-audio-0.1.0.jar
```

`libs/voicechat-api.jar` 是 **compileOnly** 依赖：只用来编译，**不打进产物**，
运行期由玩家客户端装的 Simple Voice Chat 提供。它**不进 git**（第三方代码 + 二进制夹具），
换机器后重跑一次上面的抽取脚本即可。

`identity-26.2.jar` 是恒等映射夹具，**不是漏配，也不能删** —— 26.2 起 Mojang 取消混淆、
`version json` 里没有 `*_mappings` 条目，而 loom 又要求 mappings 配置非空，
所以用一份空表的 tiny v2（`tiny 2 0 official named`）顶上。删了构建直接挂。

### 已知问题：`gradle runClient` 跑不起来（不影响发布）

报错：`Failed to read classTweaker file from mod fabric-block-api-v1:
Namespace (named) does not match current runtime namespace (official)`。

原因：26.2 没有 intermediary，我们用「恒等映射」绕过 loom 的映射下载，这会让 loom 认为
运行时命名空间是 `official`；而 maven 上的 fabric-api 子模块，其 access widener 文件头写的是
`named`。两者对不上，开发客户端在 mod 解析阶段就退出。

**这只是开发环境的问题**：正式客户端里装的是发布版 fabric-api，命名空间一致。
`build` 产出的 jar 完全正常。要看界面效果只能装到真客户端里看。

换成 `tiny 2 0 named named` 的恒等映射也不行 —— 运行时命名空间由 loom 决定，不由映射文件决定。

---

## 下载

到 [Releases](https://github.com/sighworld/sight-audio/releases) 下载这三个 jar：

| 文件 | 说明 |
|---|---|
| `sight-audio-0.1.0.jar` | 本 mod（**必需**） |
| `fabric-api-0.161.0+26.2.jar` | Simple Voice Chat 依赖它 |
| `voicechat-fabric-2.6.26.jar` | Simple Voice Chat（语音通道） |

三个一起丢进 `.minecraft/mods/`。Fabric Loader 再用官方安装器装一次
（Loader 塞不进 mods 文件夹，必须走安装器），就能进游戏了。

---

## 许可证

MIT，见 [LICENSE](LICENSE)。
