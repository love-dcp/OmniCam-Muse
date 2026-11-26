# OmniCam-Muse 交接文档 —— r522 待修（2026-11-25 07:35）

> 接手前先读 `.workbuddy/memory/MEMORY.md`（已更新红线）。本文档只写本轮新增的结论、现场与下一步。

## 0. 一句话结论

**r520 和 r521 把「大师」和「专业视频」的身份认反了。** r521 现装版本把整套 Log LUT 管线划给了 U0=`professional`（其实是大师），所以大师预览过艳、实况发灰依旧；专业视频（U0=`masterVideo`）反而被拆掉了 LUT 逻辑。

## 1. 模式身份定案（dex + 资源实证，推翻上一轮结论）

| 用户口中的模式 | U0 名 | 类 | 证据 |
|---|---|---|---|
| **大师**（拍照、预设、实况） | `professional` | `Lfm/u1;` ProfessionalCapMode（运行时 session 报 `gm.m0`） | 资源 `camera_setting_mag_handle_change_mode_names`=[视频,**大师**,人像] 对应 values `[common_video,"professional","portrait"]`；模块现有代码 FilterScope/MasterColor/MasterPalette/GrPreviewStreamRestore 全部写死 `"professional"`=大师 |
| **专业视频**（Log / LUT 直出） | `masterVideo` | `Lrm/e;` MasterVideoMode（extends module.a） | `rm/e.Y7(th)` 给 HAL 下发 `KEY_CAMERA_MODE`：`CameraConfig("com.oplus.feature.master.video.camera.mode.support")` 真→`"master_video_mode"`，否则→`"pro_video_mode"`；`vk()` 为真→`"pro_video_log_mode"`；同方法下发 `KEY_CONFIGURE_MASTER_VIDEO_CUSTOM_LUT_NAME`、`KEY_CONFIGURE_LOG_VIDEO_MODE`、`VIDEO_10BIT_ENABLE` |
| `masterVideo`（大师视频） | 同上，就是专业视频 | 同上 | 文案 `camera_mode_master_video`=大师视频；`professionalVideo` 作为 U0 名在 dex 中**不存在**，「专业视频」不是独立模式 |

注意：**不要再用 `@ProVideoMode` 指纹命中的类名去推断语义**，它在 317 命中 `rm.e`=专业视频本身；上一轮「=大师」的结论是错的。名字一律用 U0 字符串判断（稳定），不比 class。

## 2. rm.e（专业视频）关键方法（已反汇编）

- `vk()Z`：`"on".equals(DataManager.b(n9.e.W0, "off"))` —— Log 视频开关（pref_log_video_key）。
- `Hl()I`：`o9.h`（MasterVideoDecision）策略 `.c(Il())` 求「类型」，取值 0/2/4/6。
- `d7(I)I`：arg≠0 走 super；否则 `Hl()` 的 2→LUT 索引 9、4→10、6→15（自定义 LUT），其它走 super。
- `Vj()I`：`o9.h.i()` 真时直出模式=`DataManager.b(n9.e.X0,0)`；否则 `t()||y()` 为真且值≠2 时把 2 折成 1；`i()` 假返回 -1。
- `o9.h`（MasterVideoDecision）：`m()`=feature[4] Log 预览、`i()`=feature[8] 自定义 LUT、`c(II)I` 读特性（模块强制 `c(3)`=1，**无模式门控**）。`c(II)I` 调用方：`rm.e`×4、`Lff/n;`×1（后者未分析，需确认是否波及大师）。
- `o9.h.m/i` 调用方：`rm.e`、`Lil/h0;`、`Lqm/w0;`、`Lqc/b;`、`CaptureParamView`。
- 相关字符串：`func_master_video_log`、`func_master_video_log_custom_lut`、`func_master_video_hdr`、`pref_master_video_format_key`、`pref_master_video_size_key`、`pref_master_video_fps_key`、`com.oplus.camera.feature.master_video_params`、`log_lut_title_pro_video_*`。

## 3. r521b 实测现场（`work/r521_live.log` / `r521_clean.log`，07:19–07:20 用户实测）

**大师（professional）被污染的直接证据：**
```
camera mode scope reason=session current=professional class=gm.m0 proVideoActive=true
persisted LUT selection at preview LUT query g1=3 h1="lut-仿理光Log2" i1=3
lut selection set to "/data/vendor/camera_process/customize_lut/lut-仿理光Log2.cube"
preview LUT contract BaseMode si original=7 effective=15      ← 模块把 stock 的 7 改成 15
HAL decision inputs: lut=true ... format=log lutName=lut-仿理光Log2
MasterColor: preview chain idle: not on the master screen（出现在 masterVideo 里）
```
即：大师预览被套上理光 Log2 自定义 LUT（过艳）；实况视频侧 `live texture sync skipped: video lut off`，封面有 LUT、视频没有（发灰）。

**专业视频（masterVideo）在 r521b 下：** `proVideoActive=false`、`recorder constructor requested=1 effective=1 target=false wanted=false`、`preview LUT contract si original=2 effective=2` —— 模块逻辑整体不生效。

**录像停止卡死（r520 日志 `work/r520_live2.log` 21:12:06–21:12:51）：** appcodec 强制后录制 44 秒（output frame 2700），用户点停止 → 21:12:50.640 `camera mode scope reason=inactive previous=masterVideo`（stock 主动 destroy 会话）→ 21:12:51.793 重建 `pro video session current=masterVideo`。典型的录制收尾失败后模式自愈重建。**相机侧具体报错没抓到**（当时只落了 LSPosedFramework）。本轮用户没复现录像，r521 日志里没有这段。

## 4. 下一步修复清单（按顺序）

1. **r522 作用域改正（确定可做）**：`LogLutDirect.java`
   - `MODE_PRO_VIDEO_NAME = "masterVideo"`，`MODE_MASTER_NAME = "professional"`（现在正好反了，见 147/149 行），同步改 530–545、622–645 行的注释。
   - `LogLut.java` 91/104/119 行的 `masterModeActive()` 门不用动，名字换对后自动正确：大师保持 stock，专业视频恢复 `m()/i()/pref_log_video_key` 三门。
   - 改完全仓 grep `MODE_PRO_VIDEO_NAME|MODE_MASTER_NAME|masterModeActive|proVideoScopeActive` 复核其它引用点。
   - `enableHalDirectStrategy` 的 `c(3)`→1 与 `installHalStrategy` 换单例是全局无门控，确认 `Lff/n;` 对 `o9.h.c(II)I` 的调用是否在大师里触发；会的话加 `proVideoModeActive` 门。
2. **录像停止卡死**：先复现抓相机自身日志再定位，不要再上 r519 的 `targetRecorder = wanted` 暴力方案。嫌疑点：appcodec 强制 `is_use_media_codec=true` + `video_stream_media_codec=false` + `is_use_fast_video_recorder=0`（`persist.omnicam.loglut.appcodec`）。临时验证：`setprop persist.omnicam.loglut.appcodec 0` 后 force-stop 相机再录，看是否还卡，以区分「app 编码器」与「LUT 直出」两条线。
3. **XPAN 实况只有封面、封面偏暗**：r521 加的 `XPAN EV set probe` 在本轮日志里**尚未核对是否命中**（用户切到 xpan 的时间点在 `r521_clean.log` 07:20:0x 段，grep `XPAN EV set` / `xpan`）。没命中说明三个 setter 形状还是不对，回到 `bb/c1` 的 `b/v/q` 重新对形状。
4. **灵感构图「画面变化较大」**：grep `r521_clean.log` 的 `track status` / `composition reason` / `toastReasons`，看无条件提升是否生效、toast 的 reason 码是什么；317 的 toast 在 `vb/c3.Z1()`。
5. 遗留：`persist.omnicam.loglut.appcodec 0` 时普通视频也无法落盘 MP4（未查）。

## 5. 当前设备与环境状态

- 设备 95c2d261，相机 7.006.317。**严禁硬重启**、绝不 kill lspd。
- 已装 **r521b**（源码=当前工作区，`module/module.prop` 961/961）。APK：`app/build/outputs/apk/release/app-release.apk`（07:07:10，md5 `d1916412fd370e7a7a6e04b8af4dc7fc`），已覆盖到 `/data/adb/modules/ricoh_pop_x9u/omnicam-muse-4.4.5-r520.apk`（**文件名仍是 r520，内容是 r521b**，别被名字骗了）。
- 设备侧有常驻 logcat 在写 `/data/local/tmp/lspd_r521.log`（root，0 字节时用 `su -c` 读）。停掉：`ps -A | grep logcat` 拿 pid 后 `kill`；**不要用 `pkill -f lspd_r521`，会把自己那条 shell 一起杀掉（exit 143）**。
- 本机 adb server 每次调用都在重启（`daemon not running; starting now`），后台 `adb logcat` 管道会被带走 —— 抓日志一律走设备侧落盘。
- 属性现值：`persist.omnicam.xpan.liveev=660`、`persist.omnicam.loglut.appcodec=1`、`persist.omnicam.comp.trackok` 未设。

## 6. 本轮新增的工具与产物（都在 `work/dex317/`）

- `c2.txt` / `c4.txt` / `c12.txt`：classes2 / 4 / 12 的 `dexdump -d` 全量输出（BaseMode 定义在 classes12）。
- `dexstr.py`：把 dexdump 的 `string@十六进制` 解成字符串（stdout 强制 UTF-8）。
- `xclass.sh <c2|c12> '<Lxx/y;>' <out>`：抽单个类，产出 `<out>_s.txt`（解好字符串）和 `<out>_m.txt`（方法名|签名|引用字符串）。
- 已抽：`rm_e_*`（专业视频）、`fm_u1_*`（大师）、`basemode_*`（BaseMode，1051 个方法）。
- `work/camera317_res.txt`：`aapt2 dump resources` 的相机资源；`work/camera317.apk` 为拉下来的相机 APK（171 MB，可删）。
- `work/r521_live.log`、`work/r521_clean.log`：本轮实测日志。
