package com.lxmusic.tv.data.download

import android.content.Context
import com.google.android.exoplayer2.ext.okhttp.OkHttpDataSource
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DataSpec
import com.google.android.exoplayer2.upstream.cache.CacheDataSource
import com.google.android.exoplayer2.upstream.cache.CacheWriter
import com.lxmusic.tv.data.cache.CacheManager
import com.lxmusic.tv.data.model.ApiResponse
import com.lxmusic.tv.data.model.AudioQuality
import com.lxmusic.tv.data.model.Song
import com.lxmusic.tv.data.source.MusicPlayResult
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * 下载状态
 */
enum class DownloadState {
    WAITING,       // 排队等待
    DOWNLOADING,   // 下载中
    COMPLETED,     // 已完成（已进「本地」缓存，离线可播）
    FAILED,        // 失败
    CANCELLED      // 已取消
}

/**
 * 下载任务
 */
data class DownloadTask(
    /** 缓存 key（平台|歌曲id|实际音质），唯一标识 */
    val key: String,
    val song: Song,
    val quality: AudioQuality,
    val state: DownloadState,
    val progress: Float = 0f,
    val sizeBytes: Long = 0L,
    val error: String? = null
)

/**
 * 音乐下载管理器（2.9 新增）
 *
 * 复用现有 SimpleCache 缓存体系：下载的音频完整写入 SimpleCache（key=平台|歌曲id|实际音质），
 * 完成后写「完整缓存标记」+ URL 缓存 + 歌曲元数据 → 自动出现在「本地」Tab，断网可离线播放。
 *
 * 串行队列：一次下载一首，其余排队；支持进度、取消。用 ExoPlayer CacheWriter 流式下载，
 * 与「边播边缓存」共用同一套 SimpleCache 与索引。
 */
class DownloadManager(
    private val context: Context,
    private val urlProvider: suspend (song: Song, quality: AudioQuality) -> ApiResponse<MusicPlayResult>
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    /** 取消标志：正在下载的任务 key 集合 */
    private val cancelledKeys = mutableSetOf<String>()
    private var worker: Job? = null

    /** 入队一首歌的下载（去重：同 key 正在下载/排队/已完成则不重复入队） */
    fun enqueue(song: Song, quality: AudioQuality) {
        val requestKey = CacheManager.songCacheKey(song, quality)
        val existing = _tasks.value.firstOrNull { it.key == requestKey }
        if (existing != null && (existing.state == DownloadState.WAITING || existing.state == DownloadState.DOWNLOADING || existing.state == DownloadState.COMPLETED)) {
            return
        }
        _tasks.update { list ->
            list.filterNot { it.key == requestKey } +
                DownloadTask(key = requestKey, song = song, quality = quality, state = DownloadState.WAITING)
        }
        ensureWorker()
    }

    /** 取消一个任务（下载中的会中断写缓存并清理半截数据） */
    fun cancel(key: String) {
        cancelledKeys.add(key)
        // 等待中的任务直接移出队列
        _tasks.update { list -> list.filterNot { it.key == key && it.state == DownloadState.WAITING } }
    }

    /** 重新下载（失败/已取消后再入队） */
    fun retry(song: Song, quality: AudioQuality) {
        val requestKey = CacheManager.songCacheKey(song, quality)
        cancelledKeys.remove(requestKey)
        _tasks.update { list ->
            list.filterNot { it.key == requestKey } +
                DownloadTask(requestKey, song, quality, DownloadState.WAITING)
        }
        ensureWorker()
    }

    /** 移除一条任务记录 */
    fun remove(key: String) {
        _tasks.update { list -> list.filterNot { it.key == key } }
        cancelledKeys.remove(key)
    }

    /** 队列串行执行器 */
    private fun ensureWorker() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            while (true) {
                val next = _tasks.value.firstOrNull { it.state == DownloadState.WAITING } ?: break
                runTask(next)
            }
        }
    }

    private suspend fun runTask(task: DownloadTask) {
        _tasks.update { list -> list.map { if (it.key == task.key) it.copy(state = DownloadState.DOWNLOADING, error = null) else it } }
        try {
            // 1. 解析播放 URL（复用播放层解析链路：音源降级 + URL 缓存 + 平台切换）
            val resp = urlProvider(task.song, task.quality)
            if (!resp.success || resp.data == null) {
                _tasks.update { list -> list.map { if (it.key == task.key) it.copy(state = DownloadState.FAILED, error = resp.message) else it } }
                return
            }
            val data = resp.data
            // 2. 用「实际音质」作缓存 key（解析可能降级，与播放缓存保持一致）
            val actualKey = CacheManager.songCacheKey(task.song, data.quality)
            if (cancelledKeys.contains(task.key)) {
                _tasks.update { list -> list.map { if (it.key == task.key) it.copy(state = DownloadState.CANCELLED) else it } }
                return
            }
            // 已完整缓存过则直接完成（避免重复下载）
            if (CacheManager.isFullyCached(context, actualKey)) {
                finish(task, data, actualKey)
                return
            }
            // 3. 流式下载到 SimpleCache（CacheWriter.cache() 阻塞直至 EOF）
            val writer = createCacheWriter(task, data.url, actualKey)
            withContext(Dispatchers.IO) {
                // 挂起协程被取消时（scope.release / 应用退出）中断阻塞写
                val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { if (cancelledKeys.contains(task.key)) writer.cancel() }
                try {
                    writer.cache()
                } finally {
                    cancelHandle?.dispose()
                }
            }
            if (cancelledKeys.contains(task.key)) {
                // 取消：清理半截缓存
                CacheManager.removeAudioByKey(context, actualKey)
                _tasks.update { list -> list.map { if (it.key == task.key) it.copy(state = DownloadState.CANCELLED) else it } }
            } else {
                finish(task, data, actualKey)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (cancelledKeys.contains(task.key)) {
                _tasks.update { list -> list.map { if (it.key == task.key) it.copy(state = DownloadState.CANCELLED) else it } }
            } else {
                _tasks.update { list -> list.map { if (it.key == task.key) it.copy(state = DownloadState.FAILED, error = e.message) else it } }
            }
        }
    }

    /** 下载成功：写缓存标记 / URL / 歌曲元数据，并把任务置为完成 */
    private fun finish(task: DownloadTask, data: MusicPlayResult, actualKey: String) {
        CacheManager.markCacheCompleted(context, actualKey)
        CacheManager.putUrl(actualKey, data.url)
        CacheManager.saveSongMeta(task.song)
        CacheManager.saveActualQuality(task.song.platform.key, task.song.id, data.quality)
        _tasks.update { list ->
            list.map { if (it.key == task.key) it.copy(state = DownloadState.COMPLETED, progress = 1f, quality = data.quality) else it }
        }
    }

    /** 构造 CacheWriter（ExoPlayer 2.19.1），监听进度更新任务状态 */
    private fun createCacheWriter(task: DownloadTask, url: String, key: String): CacheWriter {
        // 上游网络源：强制 Range: bytes=0-（部分 CDN 对不带 Range 的普通 GET 返回 0 字节）
        val httpSource = OkHttpDataSource.Factory(okhttp3.OkHttpClient()).createDataSource()
        val rangeSource = object : DataSource {
            override fun addTransferListener(transferListener: com.google.android.exoplayer2.upstream.TransferListener) =
                httpSource.addTransferListener(transferListener)
            override fun open(dataSpec: DataSpec): Long {
                val spec = if (dataSpec.position == 0L && dataSpec.length == com.google.android.exoplayer2.C.LENGTH_UNSET.toLong()) {
                    dataSpec.withAdditionalHeaders(mapOf("Range" to "bytes=0-"))
                } else {
                    dataSpec
                }
                return httpSource.open(spec)
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                httpSource.read(buffer, offset, length)
            override fun getUri(): android.net.Uri? = httpSource.uri
            override fun getResponseHeaders(): Map<String, List<String>> = httpSource.responseHeaders
            override fun close() = httpSource.close()
        }
        val cache = CacheManager.getAudioCache(context)
        // 关键：自定义 CacheKeyFactory，让下载缓存键 = 歌曲缓存键（平台|id|音质），
        // 与「边播边缓存」写入的键完全一致 → 下载完成后播放直接命中，离线可播。
        // 默认实现按 DataSpec.uri 生成键，会导致下载的缓存读不到。
        val keyFactory = com.google.android.exoplayer2.upstream.cache.CacheKeyFactory { _: DataSpec -> key }
        // 缓存读写数据源：写进同一套 SimpleCache（CacheDataSink 落盘），与「边播边缓存」共用索引
        val cacheDataSource = CacheDataSource(
            cache,
            rangeSource,                                      // 网络源（读）
            rangeSource,                                      // 网络源（写，经 CacheDataSink 落盘）
            com.google.android.exoplayer2.upstream.cache.CacheDataSink(cache, 5L * 1024 * 1024),
            com.google.android.exoplayer2.upstream.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR,
            null,                                             // EventListener（默认）
            keyFactory
        )
        return CacheWriter(
            cacheDataSource,
            DataSpec(android.net.Uri.parse(url)),
            ByteArray(256 * 1024),
            object : CacheWriter.ProgressListener {
                override fun onProgress(fileLength: Long, bytesCached: Long, newBytesCached: Long) {
                    val p = if (fileLength > 0) (bytesCached.toFloat() / fileLength).coerceIn(0f, 1f) else 0f
                    _tasks.update { list ->
                        list.map { if (it.key == task.key) it.copy(progress = p, sizeBytes = bytesCached) else it }
                    }
                }
            }
        )
    }

    fun release() {
        scope.cancel()
    }
}