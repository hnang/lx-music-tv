package com.lxmusic.tv.presentation.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lxmusic.tv.data.download.DownloadState
import com.lxmusic.tv.data.download.DownloadTask
import com.lxmusic.tv.data.model.Song
import com.lxmusic.tv.presentation.theme.LXOnCardDark
import com.lxmusic.tv.presentation.theme.LXPrimary

/**
 * 下载全局状态（MainActivity 用 CompositionLocalProvider 提供，首页/播放页直接读取，
 * 避免把下载任务列表与回调层层穿透到每个歌曲行）
 */
val LocalDownloadTasks = staticCompositionLocalOf<List<DownloadTask>> { emptyList() }
/** 请求下载（弹出音质选择） */
val LocalDownloadOnRequest = staticCompositionLocalOf<(Song) -> Unit> { {} }
/** 取消下载 */
val LocalDownloadOnCancel = staticCompositionLocalOf<(String) -> Unit> { {} }

/**
 * 歌曲行的下载按钮（2.9）
 *
 * 根据歌曲当前下载任务状态展示不同图标/交互：
 * - 无任务：下载图标 → 点击请求下载（弹音质选择）
 * - 排队中：时钟图标（不可点）
 * - 下载中：进度环 → 点击取消
 * - 已完成：对勾（不可点）
 * - 失败：重试图标 → 点击重新入队
 */
@Composable
fun DownloadButton(
    song: Song,
    tint: Color = LXOnCardDark,
    modifier: Modifier = Modifier
) {
    val tasks = LocalDownloadTasks.current
    val onRequest = LocalDownloadOnRequest.current
    val onCancel = LocalDownloadOnCancel.current
    val task = tasks.firstOrNull { it.song.id == song.id && it.song.platform == song.platform }
    when (task?.state) {
        DownloadState.DOWNLOADING -> {
            Box(
                modifier = modifier
                    .size(40.dp)
                    .lxCircleButtonFocus()
                    .clickable { onCancel(task.key) },
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    progress = { task.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.size(26.dp),
                    strokeWidth = 3.dp,
                    color = LXPrimary,
                    trackColor = tint.copy(alpha = 0.2f)
                )
            }
        }
        DownloadState.WAITING -> {
            Box(
                modifier = modifier.size(40.dp).lxCircleButtonFocus(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Schedule,
                    contentDescription = "排队下载中",
                    tint = tint,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        DownloadState.COMPLETED -> {
            Box(
                modifier = modifier.size(40.dp).lxCircleButtonFocus(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "已下载",
                    tint = LXPrimary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        DownloadState.FAILED -> {
            Box(
                modifier = modifier.size(40.dp).lxCircleButtonFocus().clickable { onRequest(song) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "下载失败，点击重试",
                    tint = tint,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        else -> {
            // 无任务 / 已取消：普通下载按钮
            Box(
                modifier = modifier.size(40.dp).lxCircleButtonFocus().clickable { onRequest(song) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = "下载",
                    tint = tint,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}