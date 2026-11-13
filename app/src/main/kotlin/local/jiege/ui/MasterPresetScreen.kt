package local.jiege.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.LruCache
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import local.jiege.settings.DiagnosticsContract
import local.jiege.settings.FilterAliases
import local.jiege.settings.MasterPresetLibrary
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * 大师预设：按 OMaster 的样子浏览——两列封面卡片，点开是带图的大图详情页，
 * 里面有拍摄建议和 8 项调色参数，可以直接写进相机的大师模式效果栏。
 *
 * 数据与致谢见 MasterPresetLibrary 的类注释；内置预设与封面图片来自 OMaster
 * （https://github.com/iCurrer/OMaster，CC BY-NC-SA 4.0）。
 *
 * 「应用到相机」走 DiagnosticsContract.METHOD_APPLY_PRESET：设置 App 调相机导出的
 * EntryProvider，相机侧 MasterPresetApply 按 drawable 资源名认出各项并写入。
 */
@Composable
fun MasterPresetScreen(padding: PaddingValues, nestedScrollConnection: NestedScrollConnection) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var presets by remember { mutableStateOf<List<MasterPresetLibrary.Preset>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var revision by remember { mutableStateOf(0) }
    var favoriteOnly by remember { mutableStateOf(false) }
    var openId by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var proBusy by remember { mutableStateOf(false) }
    var dump by remember { mutableStateOf<String?>(null) }
    // 导入前先攒在手里：文件选完，弹窗让用户自己给这批预设起名，确认了才落库。
    var pendingImport by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var renaming by remember { mutableStateOf(false) }

    LaunchedEffect(revision) {
        presets = withContext(Dispatchers.IO) { MasterPresets.library(context).presets() }
        loaded = true
    }

    fun toggleFavorite(preset: MasterPresetLibrary.Preset) {
        scope.launch {
            withContext(Dispatchers.IO) {
                MasterPresets.library(context).setFavorite(preset.id, !preset.favorite)
            }
            revision++
        }
    }

    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = "import"
        scope.launch {
            // 先只把文件读成行格式、数一下有几条，名字等用户在弹窗里输入后再落库。
            val prepared = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: return@runCatching null
                    // 选到图片、压缩包时按文本解析会拆出满屏乱码——先按二进制拒掉。
                    if (looksBinary(bytes)) return@runCatching null
                    val text = bytes.toString(Charsets.UTF_8)
                    // OMaster 的 presets.json 是 JSON，本模块自己的导出是行格式。
                    val converted = if (text.trimStart().startsWith("{")) {
                        runCatching { omasterJsonToText(text) }.getOrDefault("")
                    } else {
                        text
                    }
                    val rows = countPresetRows(converted)
                    if (rows == 0) null else converted to rows
                }.getOrNull()
            }
            busy = null
            if (prepared == null) {
                Toast.makeText(
                    context,
                    "这不是预设文件：支持 OMaster 的 presets.json，或本模块导出的预设文本",
                    Toast.LENGTH_LONG,
                ).show()
            } else {
                pendingImport = prepared
            }
        }
    }

    val shown = if (favoriteOnly) presets.filter { it.favorite } else presets
    val open = presets.firstOrNull { it.id == openId }

    // 导入时自己起名：只有一条就直接用这个名字，多条按「名字 1 / 名字 2」编号；留空保留文件里的原名。
    pendingImport?.let { (text, count) ->
        NameFieldDialog(
            title = "导入 $count 条预设",
            summary = "给它们起个名字：只有一条时就用这个名字，多条会自动加编号；留空则用文件里的名字。",
            initial = "",
            placeholder = if (count == 1) "预设名（可留空）" else "名字前缀（可留空）",
            confirmText = "导入",
            onConfirm = { name ->
                pendingImport = null
                busy = "import"
                scope.launch {
                    val added = withContext(Dispatchers.IO) {
                        runCatching { MasterPresets.library(context).importText(text, name) }.getOrDefault(0)
                    }
                    busy = null
                    revision++
                    Toast.makeText(
                        context,
                        if (added > 0) "已导入 $added 条预设" else "没有读到可识别的预设",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            },
            onDismiss = { pendingImport = null },
        )
    }

    if (open != null && renaming) {
        val target = open
        NameFieldDialog(
            title = "重命名预设",
            summary = "只改这条预设在你库里的名字；下次写入相机时，相机里那一条也会跟着用新名字。",
            initial = target.name,
            placeholder = "预设名",
            confirmText = "保存",
            onConfirm = { name ->
                renaming = false
                if (name.isNotEmpty()) {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            MasterPresets.library(context).rename(target.id, name)
                        }
                        revision++
                        Toast.makeText(context, if (ok) "已改名为 $name" else "名字没有变化", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onDismiss = { renaming = false },
        )
    }

    if (open != null) {
        PresetDetail(
            preset = open,
            padding = padding,
            nestedScrollConnection = nestedScrollConnection,
            busy = busy == open.id,
            proBusy = proBusy,
            onBack = { openId = null },
            onFavorite = { toggleFavorite(open) },
            onRename = { renaming = true },
            onProRead = {
                if (!proBusy && busy == null) {
                    proBusy = true
                    scope.launch {
                        val values = withContext(Dispatchers.IO) { readProFromCamera(context) }
                        proBusy = false
                        if (values == null) {
                            Toast.makeText(context, "没有读到相机 Pro 参数：确认相机已在大师 Pro 档", Toast.LENGTH_LONG).show()
                        } else {
                            withContext(Dispatchers.IO) {
                                MasterPresets.library(context).setPro(
                                    open.id,
                                    values[DiagnosticsContract.KEY_PRESET_ISO].orEmpty(),
                                    values[DiagnosticsContract.KEY_PRESET_SHUTTER].orEmpty(),
                                    values[DiagnosticsContract.KEY_PRESET_EV].orEmpty(),
                                    values[DiagnosticsContract.KEY_PRESET_FOCUS].orEmpty(),
                                    values[DiagnosticsContract.KEY_PRESET_WHITE_BALANCE].orEmpty(),
                                )
                            }
                            revision++
                            Toast.makeText(context, "已把相机当前 Pro 参数抄进这条预设", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            },
            onApply = {
                busy = open.id
                scope.launch {
                    val plan = presetArgs(context, open)
                    val args = plan.args
                    try {
                        // 先排队：相机不在大师模式也能落地，进大师模式时自己写入。
                        withContext(Dispatchers.IO) { MasterPresets.queue(context, args) }
                        // 相机那边可能在等主线程，无限期挂着会把按钮永远锁死，到点就当排队处理。
                        val answer = withTimeoutOrNull(6000) {
                            withContext(Dispatchers.IO) { callCamera(context, args) }
                        }
                        if (answer == null) {
                            Toast.makeText(context, "相机没有及时应答：预设已排队，进大师模式后自动写入", Toast.LENGTH_LONG).show()
                            return@launch
                        }
                        if (answer.applied > 0) {
                            withContext(Dispatchers.IO) { MasterPresets.clearQueue(context) }
                        }
                        val text = when {
                            answer.offline -> answer.text + "（预设已排队，切到大师模式后自动写入）"
                            plan.hint.isEmpty() -> answer.text
                            else -> plan.hint + "；" + answer.text
                        }
                        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
                    } finally {
                        busy = null
                    }
                }
            },
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 筛选条固定在顶部：跟着列表滚会把入口滚没。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 五个 chip 在窄屏放不下，横着滚：挤成两行会把下面的封面顶掉一截。
                .horizontalScroll(rememberScrollState())
                .padding(
                    start = 12.dp, end = 12.dp,
                    top = padding.calculateTopPadding() + 6.dp,
                    bottom = 8.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Chip(text = "全部 ${presets.size}", selected = !favoriteOnly) { favoriteOnly = false }
            Chip(text = "收藏 ${presets.count { it.favorite }}", selected = favoriteOnly) { favoriteOnly = true }
            Chip(text = if (busy == "dump") "读取中" else "核对", selected = false) {
                if (busy != null) return@Chip
                busy = "dump"
                scope.launch {
                    try {
                        val text = withTimeoutOrNull(6000) {
                            withContext(Dispatchers.IO) {
                                callCamera(context, Bundle().apply {
                                    putString(DiagnosticsContract.KEY_PRESET_ACTION, DiagnosticsContract.ACTION_DUMP)
                                }).text
                            }
                        }
                        dump = text ?: "相机没有及时应答：确认模块已在 LSPosed 中勾选相机，再重启一次相机进程"
                        Toast.makeText(context, if (text == null) "核对超时" else "已读取相机效果栏", Toast.LENGTH_SHORT).show()
                    } finally {
                        busy = null
                    }
                }
            }
            Chip(text = if (busy == "import") "导入中" else "导入", selected = false) {
                if (busy == null) import.launch(arrayOf("application/json", "text/plain", "*/*"))
            }
            // 相机存储里一旦混进「内置滤镜的副本」，预设条上「标准」「清透」就会各多一张，
            // 而且相机自己的长按删除只认 custom_<n>，根本删不掉。这个入口让相机进程去清。
            Chip(text = "清理", selected = false) {
                if (busy != null) return@Chip
                MasterPresets.queue(context, Bundle().apply {
                    putString(DiagnosticsContract.KEY_PRESET_ACTION, "cleanpresets")
                })
                Toast.makeText(
                    context,
                    "已交给相机清理：重复的「标准」「清透」在下次启动相机后消失",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize().weight(1f).overScrollVertical().nestedScroll(nestedScrollConnection),
        contentPadding = PaddingValues(
            start = 12.dp, end = 12.dp,
            top = 4.dp,
            bottom = padding.calculateBottomPadding() + 16.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {

        if (shown.isEmpty()) {
            item(key = "empty", span = { GridItemSpan(2) }) {
                Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = if (loaded) "还没有收藏的预设" else "正在读取预设",
                            fontSize = 16.sp,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = if (loaded) "在预设封面右上角点一下心形就会收进这里；也可以点右上角“导入”。"
                            else "首次启动会把内置的预设和封面一起写进来。",
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }

        // 核对结果放在最上面：点完马上能看到，不用滚到底。
        if (dump != null) {
            item(key = "dump", span = { GridItemSpan(2) }) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = dump ?: "",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }

        items(shown.size, key = { shown[it].id }) { index ->
            val preset = shown[index]
            PresetCoverCard(
                preset = preset,
                onOpen = { openId = preset.id },
                onFavorite = { toggleFavorite(preset) },
            )
        }

        item(key = "credits", span = { GridItemSpan(2) }) {
            val uriHandler = LocalUriHandler.current
            Card(modifier = Modifier.fillMaxWidth(), onClick = { uriHandler.openUri("https://github.com/iCurrer/OMaster") }) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "致谢 · OMaster", fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurface)
                    Text(
                        text = "预设参数、拍摄建议与封面照片来自 OMaster —— 大师模式调色参数库，"
                            + "作者 Silas（@iCurrer）、Luminary（@fengyec2）及各位预设作者；"
                            + "项目以 CC BY-NC-SA 4.0 发布，本模块免费非商业使用并按相同许可共享。",
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
    }
}

/** 一列里的封面卡片：图上盖标题与作者，右上角收藏。 */
@Composable
private fun PresetCoverCard(
    preset: MasterPresetLibrary.Preset,
    onOpen: () -> Unit,
    onFavorite: () -> Unit,
) {
    val context = LocalContext.current
    val cover = rememberAssetImage(context, preset.cover)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.75f)
            .clip(RoundedCornerShape(20.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onOpen),
    ) {
        if (cover != null) {
            Image(
                bitmap = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize().background(
                    Brush.linearGradient(
                        listOf(
                            MiuixTheme.colorScheme.primary.copy(alpha = 0.45f),
                            MiuixTheme.colorScheme.surfaceContainerHighest,
                        ),
                    ),
                ),
            )
        }
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0.45f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.78f),
                ),
            ),
        )
        Column(
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
        ) {
            Text(
                text = preset.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (preset.author.isNotEmpty()) {
                Text(
                    text = preset.author,
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp)
                .size(30.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(onClick = onFavorite),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (preset.favorite) HeartFilledIcon else HeartIcon,
                contentDescription = if (preset.favorite) "取消收藏" else "收藏",
                tint = if (preset.favorite) Color(0xFFFF7A8A) else Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** 详情页：大图、拍摄建议、调色参数，底部一键写入相机。 */
@Composable
private fun PresetDetail(
    preset: MasterPresetLibrary.Preset,
    padding: PaddingValues,
    nestedScrollConnection: NestedScrollConnection,
    busy: Boolean,
    proBusy: Boolean,
    onBack: () -> Unit,
    onFavorite: () -> Unit,
    onRename: () -> Unit,
    onProRead: () -> Unit,
    onApply: () -> Unit,
) {
    val images = preset.images()
    var tipsOpen by remember(preset.id) { mutableStateOf(true) }
    val pager = rememberPagerState(pageCount = { if (images.isEmpty()) 1 else images.size })
    // 系统返回键先回列表，不然会直接把整个设置页关掉。
    BackHandler { onBack() }

    LazyColumn(
        modifier = Modifier.fillMaxSize().overScrollVertical().nestedScroll(nestedScrollConnection),
        contentPadding = PaddingValues(
            bottom = padding.calculateBottomPadding() + 24.dp,
        ),
    ) {
        item(key = "header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = padding.calculateTopPadding() + 4.dp, start = 8.dp, end = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = PresetBackIcon,
                        contentDescription = "返回",
                        tint = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(
                        text = preset.name,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (preset.author.isNotEmpty()) {
                        Text(
                            text = preset.author,
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Box(
                    modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onFavorite),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (preset.favorite) HeartFilledIcon else HeartIcon,
                        contentDescription = if (preset.favorite) "取消收藏" else "收藏",
                        tint = if (preset.favorite) Color(0xFFFF7A8A) else MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
        item(key = "gallery") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .aspectRatio(0.75f)
                    .clip(RoundedCornerShape(22.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainer),
            ) {
                if (images.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize().background(
                            Brush.linearGradient(
                                listOf(
                                    MiuixTheme.colorScheme.primary.copy(alpha = 0.45f),
                                    MiuixTheme.colorScheme.surfaceContainerHighest,
                                ),
                            ),
                        ),
                    )
                } else {
                    HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                        val image = rememberAssetImage(LocalContext.current, images[page])
                        if (image != null) {
                            Image(
                                bitmap = image,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    if (images.size > 1) {
                        Row(
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            repeat(images.size) { index ->
                                Box(
                                    modifier = Modifier
                                        .size(if (pager.currentPage == index) 7.dp else 5.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (pager.currentPage == index) Color.White
                                            else Color.White.copy(alpha = 0.5f),
                                        ),
                                )
                            }
                        }
                    }
                }
            }
        }
        if (preset.tags.isNotEmpty()) {
            item(key = "tags") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (tag in preset.tags.split(",")) {
                        if (tag.isBlank()) continue
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                                .padding(horizontal = 12.dp, vertical = 5.dp),
                        ) {
                            Text(
                                text = tag.trim(),
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                            )
                        }
                    }
                }
            }
        }
        item(key = "tips") {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 12.dp),
                onClick = { tipsOpen = !tipsOpen },
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "拍摄建议",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = if (tipsOpen) "收起" else "展开",
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    AnimatedVisibility(visible = tipsOpen) {
                        Column(modifier = Modifier.padding(top = 8.dp)) {
                            if (preset.tips.isBlank()) {
                                Text(
                                    text = "这条预设没有留下拍摄建议。",
                                    fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            for (line in preset.tipLines()) {
                                if (line.isBlank()) continue
                                Text(
                                    text = line,
                                    fontSize = 13.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.padding(bottom = 3.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        item(key = "params-title") {
            Text(
                text = "调色参数",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
            )
        }
        item(key = "filter") {
            ParamTile(
                label = "滤镜",
                value = preset.filter.ifEmpty { "—" },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
        }
        item(key = "row1") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ParamTile(label = "柔光", value = preset.softLight.ifEmpty { "—" }, modifier = Modifier.weight(1f))
                ParamTile(label = "影调", value = signed(preset.tone), modifier = Modifier.weight(1f))
            }
        }
        item(key = "row2") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ParamTile(label = "饱和度", value = signed(preset.saturation), modifier = Modifier.weight(1f))
                ParamTile(label = "冷暖", value = signed(preset.warmCool), modifier = Modifier.weight(1f))
            }
        }
        item(key = "row3") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ParamTile(label = "青品", value = signed(preset.cyanMagenta), modifier = Modifier.weight(1f))
                ParamTile(label = "锐度", value = signed(preset.sharpness), modifier = Modifier.weight(1f))
            }
        }
        item(key = "vignette") {
            ParamTile(
                label = "暗角",
                value = when {
                    preset.vignette.isEmpty() -> "—"
                    preset.vignetteOn() -> "开启"
                    else -> "关闭"
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 10.dp),
            )
        }
        item(key = "pro-title") {
            Text(
                text = "Pro 参数",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
            )
        }
        item(key = "pro1") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ParamTile(label = "ISO", value = preset.iso.ifEmpty { "—" }, modifier = Modifier.weight(1f))
                ParamTile(label = "快门", value = preset.shutter.ifEmpty { "—" }, modifier = Modifier.weight(1f))
                ParamTile(label = "EV", value = preset.exposureCompensation.ifEmpty { "—" }, modifier = Modifier.weight(1f))
            }
        }
        item(key = "pro2") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ParamTile(label = "对焦 AF", value = preset.focus.ifEmpty { "—" }, modifier = Modifier.weight(1f))
                ParamTile(label = "白平衡", value = preset.whiteBalance.ifEmpty { "—" }, modifier = Modifier.weight(1f))
            }
        }
        item(key = "pro-read") {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 10.dp)) {
                TextButton(
                    text = if (proBusy) "读取中" else "读取相机当前 Pro 参数",
                    enabled = !proBusy && !busy,
                    onClick = onProRead,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "先在大师 Pro 档把 ISO / 快门 / EV / 对焦 / 白平衡调好，再点这里抄进这条预设；" +
                        "之后写入时这五项会一起下发。空着表示不管这一项。",
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                )
            }
        }
        item(key = "rename") {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 12.dp)) {
                TextButton(
                    text = "重命名这条预设",
                    onClick = onRename,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "改名只影响显示与写入相机时的预设名，调色参数不变。",
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                )
            }
        }
        item(key = "apply") {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 18.dp)) {
                TextButton(
                    text = if (busy) "写入中" else "写入相机大师模式",
                    enabled = !busy,
                    onClick = onApply,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "相机需要是大师模式且在前台；滤镜与柔光在部分机型上仍要手动确认。",
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun ParamTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, cornerRadius = 18.dp) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = label,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Text(
                text = value,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 4.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(
                if (selected) MiuixTheme.colorScheme.primary
                else MiuixTheme.colorScheme.surfaceContainerHigh,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

private fun signed(value: Int?): String = when {
    value == null -> "—"
    value > 0 -> "+$value"
    else -> value.toString()
}

/** 解码过的封面缓存：列表滚动时不必反复从 assets 读盘。 */
private val presetImages = object : LruCache<String, ImageBitmap>(16) {}

@Composable
private fun rememberAssetImage(context: Context, path: String): ImageBitmap? {
    var image by remember(path) { mutableStateOf(presetImages.get(path)) }
    LaunchedEffect(path) {
        if (path.isEmpty()) {
            image = null
            return@LaunchedEffect
        }
        presetImages.get(path)?.let {
            image = it
            return@LaunchedEffect
        }
        val decoded = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open(path).use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
            }.getOrNull()
        }
        if (decoded != null) presetImages.put(path, decoded)
        image = decoded
    }
    return image
}

/** 二进制文件（图片、压缩包）按文本解析只会得到乱码，先看头 4KB 有没有 NUL / 大量控制字符。 */
private fun looksBinary(bytes: ByteArray): Boolean {
    val head = bytes.take(4096)
    if (head.isEmpty()) return true
    var control = 0
    for (byte in head) {
        val value = byte.toInt() and 0xFF
        if (value == 0) return true
        if (value < 9 || (value in 14..31)) control++
    }
    return control * 20 > head.size
}

/** 行格式文本里有几条像样的预设：至少 14 列，跳过注释与版本行。 */
private fun countPresetRows(text: String): Int = text.lineSequence().count { raw ->
    val line = raw.trim()
    line.isNotEmpty() && !line.startsWith("#") && line != "v2" && raw.count { it == '\t' } >= 13
}

/** 一条预设变成相机侧的入参；没有的项不下发，相机保持原值。 */
private fun presetArgs(context: Context, preset: MasterPresetLibrary.Preset): PresetPlan {
    val args = Bundle()
    var hint = ""
    args.putString(DiagnosticsContract.KEY_PRESET_ACTION, DiagnosticsContract.ACTION_APPLY)
    preset.tone?.let { args.putInt(DiagnosticsContract.KEY_PRESET_TONE, it) }
    preset.saturation?.let { args.putInt(DiagnosticsContract.KEY_PRESET_SATURATION, it) }
    preset.warmCool?.let { args.putInt(DiagnosticsContract.KEY_PRESET_WARM_COOL, it) }
    preset.cyanMagenta?.let { args.putInt(DiagnosticsContract.KEY_PRESET_CYAN_MAGENTA, it) }
    preset.sharpness?.let { args.putInt(DiagnosticsContract.KEY_PRESET_SHARPNESS, it) }
    if (preset.vignette.isNotEmpty()) {
        args.putInt(DiagnosticsContract.KEY_PRESET_VIGNETTE, if (preset.vignetteOn()) 100 else 0)
    }
    if (preset.filter.isNotEmpty()) {
        val filter = preset.filterName()
        args.putString(DiagnosticsContract.KEY_PRESET_FILTER, filter)
        args.putInt(DiagnosticsContract.KEY_PRESET_FILTER_STRENGTH, preset.filterStrength())
        // 预设名始终带上：本机没有这个滤镜时要靠它落一条自定义预设。
        args.putString(DiagnosticsContract.KEY_PRESET_NAME, preset.name)
        val rows = filterRows(context)
        val exact = rows?.firstOrNull { it.title == filter }
        // 滤镜表里的名字可能被清空，按别名登记过的 LUT 文件名再找一次。
        val lut = FilterAliases.lutOf(filter)
        val renamed = (if (exact == null) rows?.firstOrNull { it.title == FilterAliases.current(filter) } else null)
            ?: lut?.let { file -> rows?.firstOrNull { it.id == file } }
        when {
            // 读不到滤镜表就当有：宁可不建预设，也别往相机里塞错东西。
            rows == null -> Unit
            // 本机就有这个滤镜：直接改它的参数，不另建预设。
            exact != null -> Unit
            // 改过名（浓郁 → 菲林）：参数写到「标准」上，再落一条绑在改名后滤镜上的预设，
            // 这样用户选中它时相机自己会把滤镜切过去。
            renamed != null -> {
                args.putBoolean(DiagnosticsContract.KEY_PRESET_CREATE, true)
                args.putString(DiagnosticsContract.KEY_PRESET_FILTER_TYPE, FilterAliases.filterTypeOf(renamed.id))
                hint = "「$filter」在本机叫「${renamed.title.ifEmpty { filter }}」，已新建预设「${preset.name}」，在预设条里选中它就会用上"
            }
            // 本机确实没有这个滤镜：参数写到「标准」上，落一条同名预设承载这套参数。
            else -> {
                args.putBoolean(DiagnosticsContract.KEY_PRESET_CREATE, true)
                hint = "本机没有「$filter」，已新建预设「${preset.name}」（绑标准滤镜），在预设条里选中它就会用上"
            }
        }
    }
    // 大师 Pro 档的拍摄参数：预设里写了哪几项就下发哪几项，没写的保持相机当前值。
    if (preset.iso.isNotEmpty()) args.putString(DiagnosticsContract.KEY_PRESET_ISO, preset.iso)
    if (preset.shutter.isNotEmpty()) args.putString(DiagnosticsContract.KEY_PRESET_SHUTTER, preset.shutter)
    if (preset.exposureCompensation.isNotEmpty()) args.putString(DiagnosticsContract.KEY_PRESET_EV, preset.exposureCompensation)
    if (preset.focus.isNotEmpty()) args.putString(DiagnosticsContract.KEY_PRESET_FOCUS, preset.focus)
    if (preset.whiteBalance.isNotEmpty()) args.putString(DiagnosticsContract.KEY_PRESET_WHITE_BALANCE, preset.whiteBalance)
    // 柔光「无」也要下发：相机里这是四档的第 0 档，不下发就等于保持上一次的档位，
    // 写完之后效果栏还停在「强」上 —— 用户看到的就是「参数对不上」。
    if (preset.softLight.isNotEmpty()) {
        args.putString(DiagnosticsContract.KEY_PRESET_SOFT_LIGHT, preset.softLight)
    }
    return PresetPlan(args, hint)
}

/** 一次下发的入参 + 给用户看的一句补充说明（滤镜名对不上、要新建预设时才有）。 */
private class PresetPlan(val args: Bundle, val hint: String)

/** 相机滤镜表；读不到返回 null（调用方按「有这个滤镜」处理）。 */
private fun filterRows(context: Context): List<local.jiege.settings.MasterFilterStore.Row>? = try {
    MasterFilters.store(context).rows()
} catch (_: Throwable) {
    null
}

/**
 * 读相机当前的 Pro 参数（ISO / 快门 / EV / 对焦 / 白平衡），返回「设置侧键 → 相机里的值」。
 * 读不到（相机没开、没在大师 Pro 档）返回 null。
 */
private fun readProFromCamera(context: Context): Map<String, String>? = try {
    val args = Bundle().apply {
        putString(DiagnosticsContract.KEY_PRESET_ACTION, DiagnosticsContract.ACTION_PRO_READ)
    }
    val result = context.contentResolver.call(
        Uri.parse("content://" + DiagnosticsContract.CAMERA_AUTHORITY),
        DiagnosticsContract.METHOD_APPLY_PRESET, null, args,
    )
    val text = result?.getString(DiagnosticsContract.KEY_PRO_RESULT).orEmpty()
    if (text.isBlank()) {
        null
    } else {
        text.lines().mapNotNull { line ->
            val eq = line.indexOf('=')
            if (eq <= 0) null else line.substring(0, eq).trim() to line.substring(eq + 1).trim()
        }.toMap()
    }
} catch (_: Exception) {
    null
}

/** 调相机的预设通道，返回给用户看的一句话。会阻塞并可能拉起相机进程。 */
/** 相机的一次应答：给用户看的一句话 + 真正写进去的项数 + 是不是根本没连上相机。 */
private class CameraAnswer(val text: String, val applied: Int, val offline: Boolean = false)

private fun callCamera(context: Context, extras: Bundle): CameraAnswer = try {
    val result = context.contentResolver.call(
        Uri.parse("content://" + DiagnosticsContract.CAMERA_AUTHORITY),
        DiagnosticsContract.METHOD_APPLY_PRESET, null, extras,
    ) ?: return CameraAnswer("相机没有应答：确认模块已在 LSPosed 中勾选相机", 0, true)
    val message = result.getString(DiagnosticsContract.KEY_PRESET_RESULT).orEmpty()
    val applied = result.getInt(DiagnosticsContract.KEY_PRESET_APPLIED)
    val text = when {
        DiagnosticsContract.ACTION_DUMP == extras.getString(DiagnosticsContract.KEY_PRESET_ACTION) ->
            result.getString(DiagnosticsContract.KEY_PRESET_ITEMS).orEmpty().ifEmpty { "没有读到效果栏" }
        message.isNotEmpty() -> message
        applied > 0 -> "已应用 $applied 项，另需在相机里确认滤镜与柔光"
        else -> "相机还没建好大师效果栏"
    }
    CameraAnswer(text, applied)
} catch (error: Exception) {
    CameraAnswer("调用相机失败：" + (error.message ?: error.toString()), 0, true)
}

/**
 * OMaster 的 presets.json（sections/items，label 是 @string 资源名）转成本模块的行格式。
 * 只取 8 个调色参数；Pro 模式参数（ISO、快门、曝光补偿、色温、色调）不在这里应用。
 * 封面字段留空：导入的 JSON 指向的是对方 App 的 assets，本模块读不到。
 */
private fun omasterJsonToText(json: String): String {
    val labels = mapOf(
        "@string/param_filter" to 4,
        "@string/param_soft_light" to 5,
        "@string/param_tone_curve" to 6,
        "@string/param_saturation" to 7,
        "@string/param_warm_cool" to 8,
        "@string/param_cyan_magenta" to 9,
        "@string/param_sharpness" to 10,
        "@string/param_vignette" to 11,
    )
    // Pro 档的五项：OMaster 那边叫什么没有准数，常见写法都收一遍，认到哪个算哪个。
    val proLabels = mapOf(
        "@string/param_iso" to 17, "@string/param_iso_value" to 17,
        "@string/param_shutter" to 18, "@string/param_shutter_speed" to 18,
        "@string/param_exposure_time" to 18,
        "@string/param_ev" to 19, "@string/param_exposure_compensation" to 19,
        "@string/param_focus" to 20, "@string/param_focus_mode" to 20, "@string/param_af" to 20,
        "@string/param_wb" to 21, "@string/param_white_balance" to 21,
    )
    val root = JSONObject(json)
    val presets = root.optJSONArray("presets") ?: return ""
    val out = StringBuilder("v2\n")
    // id 由名字算出来：同一个文件重复导入会覆盖同一条，改过名字的另存一条，不会互相顶掉。
    val usedIds = HashSet<String>()
    for (i in 0 until presets.length()) {
        val preset = presets.optJSONObject(i) ?: continue
        val rawName = preset.optString("name").trim()
        if (rawName.startsWith("_")) continue
        // 没有名字的条目留着也点不开（库要求名字非空），先给个能改的默认名。
        val name = rawName.ifEmpty { "导入 ${i + 1}" }
        val base = "import_" + Integer.toHexString(name.hashCode())
        var id = base
        var duplicate = 2
        while (!usedIds.add(id)) {
            id = base + "_" + duplicate
            duplicate++
        }
        val fields = arrayOf("", id, name, preset.optString("author"),
            "", "", "", "", "", "", "", "", "", "", "0", "", "", "", "", "", "", "")
        var tips = ""
        val sections = preset.optJSONArray("sections")
        if (sections != null) {
            for (s in 0 until sections.length()) {
                val items = sections.optJSONObject(s)?.optJSONArray("items") ?: continue
                for (k in 0 until items.length()) {
                    val item = items.optJSONObject(k) ?: continue
                    val label = item.optString("label")
                    val column = labels[label] ?: proLabels[label] ?: continue
                    fields[column] = item.optString("value")
                }
            }
        }
        preset.optJSONObject("description")?.let { tips = it.optString("content") }
        if (tips.isEmpty()) tips = preset.optString("shootingTips")
        fields[12] = tips
        fields[13] = preset.optJSONArray("tags")?.let { tags ->
            (0 until tags.length()).joinToString(",") { tags.optString(it) }
        } ?: ""
        // 数值去掉前导 +；滤镜与柔光保留原样，供相机侧按名称匹配。
        for (column in 6..10) fields[column] = fields[column].removePrefix("+")
        out.append(fields.joinToString("\t") { it.replace("\t", " ").replace("\n", "\\n") }).append('\n')
    }
    return out.toString()
}
