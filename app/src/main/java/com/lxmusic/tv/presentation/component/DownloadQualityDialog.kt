package com.lxmusic.tv.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lxmusic.tv.data.model.AudioQuality
import com.lxmusic.tv.data.model.Song
import com.lxmusic.tv.presentation.theme.LXPrimary
import com.lxmusic.tv.presentation.theme.LXSurfaceDialog
import com.lxmusic.tv.presentation.theme.LXSurfaceVariant
import com.lxmusic.tv.presentation.theme.LXTextPrimary

/**
 * 下载音质选择弹窗（2.9）：下载前让用户选音质。
 * 四个选项：标准/高品质/无损/Hi-Res，点击即开始下载。
 */
@Composable
fun DownloadQualityDialog(
    song: Song,
    onSelect: (Song, AudioQuality) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LXSurfaceDialog,
        title = {
            Text("下载到本地", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = LXTextPrimary)
        },
        text = {
            Column {
                Text(
                    text = "《${song.name}》 ${song.singer}",
                    fontSize = 14.sp,
                    color = LXTextPrimary,
                    maxLines = 2
                )
                Column(modifier = Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val qualities = listOf(
                        AudioQuality.QUALITY_128K,
                        AudioQuality.QUALITY_320K,
                        AudioQuality.FLAC,
                        AudioQuality.FLAC_24BIT
                    )
                    qualities.forEach { q ->
                        val label = when (q) {
                            AudioQuality.QUALITY_128K -> "标准 128K"
                            AudioQuality.QUALITY_320K -> "高品质 320K"
                            AudioQuality.FLAC -> "无损 FLAC"
                            AudioQuality.FLAC_24BIT -> "Hi-Res FLAC 24bit"
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(LXSurfaceVariant)
                                .clickable { onSelect(song, q) },
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = label,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = LXPrimary,
                                modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = LXTextPrimary) }
        }
    )
}