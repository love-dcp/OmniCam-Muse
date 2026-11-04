package local.jiege.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import local.jiege.settings.ModuleSettings
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle

/**
 * The 照片 card: natural photo mode (a master switch that opens the two features it turns off, both on
 * by default, and the extra softening slider, 0 % = stock) and the 25MP switch.
 *
 * The camera process cannot read the module's preferences (different uid and SELinux categories),
 * so every control here also mirrors into a property the camera reads live:
 * persist.omnicam.aura.natural (bit 1 master, 2 no AI detail, 4 no LCE) and
 * persist.omnicam.aura.naturalsoften (percent). On PKJ110 (X8U) the LCE keys do not exist in the
 * stock tuning json and the soften patch site is absent, so those two rows stay hidden there.
 */
@Composable
internal fun PhotoSection(store: SettingsStore, cardModifier: Modifier) {
    var natural by remember { mutableStateOf(store.get(ModuleSettings.NATURAL_MODE)) }
    LaunchedEffect(Unit) { pushNatural(store) }
    SmallTitle(text = "照片")
    Card(modifier = cardModifier) {
        SettingSwitch(
            store = store,
            key = ModuleSettings.NATURAL_MODE,
            title = "拍照模式自然化",
            summary = "关闭算法重绘与锐化，画面更接近直出；改变后重启相机生效",
            onChange = {
                natural = it
                pushNatural(store)
            },
        )
        AnimatedVisibility(visible = natural) {
            Column {
                SettingSwitch(
                    store = store,
                    key = ModuleSettings.NATURAL_NO_AI_DETAIL,
                    title = "关闭 AI 细节增强",
                    summary = "文字和纹理不再被网络重绘，同时关闭头发锐化",
                    onChange = { pushNatural(store) },
                )
                if (ModuleSettings.isPma110()) {
                    SettingSwitch(
                        store = store,
                        key = ModuleSettings.NATURAL_NO_LCE,
                        title = "关闭局部对比度增强（LCE）",
                        onChange = { pushNatural(store) },
                    )
                    SettingPercent(
                        store = store,
                        key = ModuleSettings.NATURAL_SOFTEN,
                        title = "进一步柔化",
                        summary = "不作用于大师模式和理光模式",
                        onChange = { pushNatural(store) },
                    )
                }
            }
        }
        SettingSwitch(
            store = store,
            key = ModuleSettings.UNLOCK_25MP,
            title = "放宽 25MP 自动切换条件",
            summary = "放宽照度、ISO、温度和运动限制。需在相机设置里选“超清优先”",
        )
    }
    SmallTitle(text = "构图")
    Card(modifier = cardModifier) {
        SettingSwitch(
            store = store,
            key = ModuleSettings.AI_COMPOSITION,
            title = "构图入口",
            summary = "在取景框右侧显示构图图标，照片模式和大师模式可用。点它打开构图面板",
        )
        LaunchedEffect(Unit) { pushInspiration(store) }
        SettingSwitch(
            store = store,
            key = ModuleSettings.INSPIRATION_COMPOSITION,
            title = "灵感构图（云端模型）",
            summary = "点构图图标时把画面交给 OPPO 的美学模型，1~3 秒返回建议框。需要联网和 com.oplus.deepthinker；关闭时全部在手机上判断，画面不上传",
            quiet = true,
            onChange = { pushInspiration(store) },
        )
    }
}

/**
 * Mirrors the inspiration switch into the property the camera reads.
 *
 * The camera process cannot read the module's preferences (different uid and SELinux categories),
 * which is why the natural-mode card does the same thing. Read live by CloudComposer.enabled(),
 * so a flip applies to the next entry tap without restarting the camera.
 */
internal fun pushInspiration(store: SettingsStore) {
    val on = store.get(ModuleSettings.INSPIRATION_COMPOSITION)
    rootCommand("setprop persist.omnicam.comp.inspiration ${if (on) 1 else 0}")
}

/** Mirrors the natural-mode switches and the soften percent into the properties the camera reads. */
internal fun pushNatural(store: SettingsStore) {
    val mask = (if (store.get(ModuleSettings.NATURAL_MODE)) 1 else 0) or
        (if (store.get(ModuleSettings.NATURAL_NO_AI_DETAIL)) 2 else 0) or
        (if (store.get(ModuleSettings.NATURAL_NO_LCE)) 4 else 0)
    rootCommand("setprop persist.omnicam.aura.natural $mask")
    rootCommand("setprop persist.omnicam.aura.naturalsoften ${store.getInt(ModuleSettings.NATURAL_SOFTEN)}")
}

/**
 * Writes every mirrored property once, from the stored settings.
 *
 * Each page used to sync its own slice in a LaunchedEffect, which means a property was only ever
 * written after the user happened to open that exact page. A full uninstall deletes all
 * persist.omnicam.* properties and pm-uninstalls the app, so after a reinstall nothing restored
 * them and features silently fell back to stock defaults -- composition fell back to the on-device
 * engine because persist.omnicam.comp.inspiration was simply missing. Running the whole set at
 * start-up makes "open the app once" enough. Call it off the main thread: every push shells out.
 */
internal fun syncMirroredProperties(store: SettingsStore) {
    pushInspiration(store)
    pushNatural(store)
    pushLevels(store)
    pushStrength(store)
}
