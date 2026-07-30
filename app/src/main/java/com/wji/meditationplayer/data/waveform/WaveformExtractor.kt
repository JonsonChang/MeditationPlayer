package com.wji.meditationplayer.data.waveform

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * 解碼音檔算出振幅峰值陣列，供波形圖使用。
 *
 * **為什麼要抽樣**：實測 Pixel 10 上，MediaCodec 每個緩衝的往返成本約 2ms，而 20 分鐘的
 * AAC 有五萬六千多個 frame，全部解完固定要 110 秒以上。管線化（保持多個緩衝在飛）只從
 * 139 秒降到 111 秒，提高執行緒優先權完全無效 —— 這個成本是解碼路徑固有的，
 * 唯一的解法是**少解一些 frame**。
 *
 * 所以長檔改成：每個 bucket 只 seek 過去解一小段連續視窗（[WINDOW_US]），
 * 掃過那段裡的每一個 sample 取峰值。連續視窗不會有 aliasing 失真；代價是每個 bucket
 * 只看區間裡的一小段，可能漏掉瞬時的最大聲。對包絡平滑的引導冥想素材影響極小。
 *
 * 短檔（視窗總長已覆蓋整個檔案）則直接循序解完，精度完全無損。
 */
object WaveformExtractor {

    /**
     * 全檔切成這麼多個 bucket。
     *
     * 波形實際只畫得出約 130–150 條（`WaveformCanvas` 的 bar 間距是 3dp），192 剛好
     * 略高於這個數字。
     *
     * 抽樣模式下每個 bucket 要付一次 seek + flush（實測約 21ms，比視窗解碼還貴），
     * 所以 bucket 數直接決定總時間。實測 20 分鐘的 m4a：512 個要 13.3 秒、256 個 7.5 秒。
     * 取 192 是為了把省下來的時間換成更長的取樣視窗（見 [WINDOW_US]）。
     */
    const val BUCKETS = 192

    /**
     * 抽樣模式下每個 bucket 解碼的連續視窗長度。
     *
     * 這個值決定波形的可信度：20 分鐘的檔案每個 bucket 涵蓋約 6 秒，視窗太短（80ms，
     * 約 1%）會讓每根 bar 變成「剛好抽到某個字還是抽到停頓」的抽獎，畫出來又尖又雜。
     * 拉到 200ms 後涵蓋率提高約 2.5 倍，波形明顯貼近真實包絡，代價只有約 1 秒。
     */
    private const val WINDOW_US = 200_000L

    private const val TAG = "WaveformExtractor"

    /** 只在整條管線都沒事可做時才用這個阻塞等待，平時走非阻塞的 0。 */
    private const val IDLE_TIMEOUT_US = 5_000L

    /** 抽樣時每個 bucket 允許的無進展次數上限，避免壞檔造成無窮迴圈。 */
    private const val MAX_STALLS = 12

    /**
     * @param onProgress 回報 0..1 的進度與**目前已填部分的複本**，讓 UI 能邊解碼邊畫。
     */
    suspend fun extract(
        context: Context,
        uri: Uri,
        durationMs: Long,
        onProgress: (Float, ShortArray) -> Unit,
    ): ShortArray? = withContext(Dispatchers.IO) {
        if (durationMs <= 0L) return@withContext null

        val startedAt = System.nanoTime()
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return@withContext null

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return@withContext null
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = max(1, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))

            val decoder = MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }
            codec = decoder

            val durationUs = durationMs * 1000L
            val sampled = BUCKETS * WINDOW_US < durationUs
            val peaks = if (sampled) {
                extractSampled(decoder, extractor, durationUs, onProgress)
            } else {
                extractSequential(decoder, extractor, durationMs, sampleRate, channelCount, onProgress)
            } ?: return@withContext null

            Log.i(
                TAG,
                "擷取完成: ${(System.nanoTime() - startedAt) / 1_000_000}ms " +
                    "(${if (sampled) "抽樣" else "循序"}, ${durationMs / 1000}s, $mime, " +
                    "${sampleRate}Hz, ${channelCount}ch)",
            )
            onProgress(1f, peaks.copyOf())
            peaks
        } catch (_: Exception) {
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * 抽樣模式：每個 bucket seek 過去解一小段連續視窗。
     * 成本與檔長無關，只跟 [BUCKETS] 成正比。
     */
    private suspend fun extractSampled(
        decoder: MediaCodec,
        extractor: MediaExtractor,
        durationUs: Long,
        onProgress: (Float, ShortArray) -> Unit,
    ): ShortArray? {
        val peaks = ShortArray(BUCKETS)
        val scanner = PeakScanner()
        val info = MediaCodec.BufferInfo()
        val scratch = Drained()
        var isFloat = false

        for (bucket in 0 until BUCKETS) {
            if (!currentCoroutineContext().isActive) return null

            extractor.seekTo(
                bucket.toLong() * durationUs / BUCKETS,
                MediaExtractor.SEEK_TO_CLOSEST_SYNC,
            )
            decoder.flush()

            var peak = 0
            var windowStartUs = -1L
            var inputDone = false
            var done = false
            var stalls = 0

            while (!done) {
                var progressed = false

                while (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(0)
                    if (inIndex < 0) break
                    val buffer = decoder.getInputBuffer(inIndex)
                    val size = if (buffer == null) -1 else extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(
                            inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                    progressed = true
                }

                var drained = drain(decoder, info, 0, scratch)
                if (!drained.handled) {
                    drained = drain(decoder, info, IDLE_TIMEOUT_US, scratch)
                }
                if (drained.handled) {
                    progressed = true
                    drained.isFloatUpdate?.let { isFloat = it }
                    if (drained.hasData) {
                        if (windowStartUs < 0) windowStartUs = drained.presentationTimeUs
                        decoder.getOutputBuffer(drained.index)?.let { out ->
                            out.position(drained.offset)
                            out.limit(drained.offset + drained.size)
                            peak = max(peak, scanner.maxMagnitude(out, isFloat))
                        }
                        if (drained.presentationTimeUs - windowStartUs >= WINDOW_US) done = true
                    }
                    if (drained.index >= 0) decoder.releaseOutputBuffer(drained.index, false)
                    if (drained.endOfStream) done = true
                }

                if (!progressed) {
                    stalls++
                    if (stalls >= MAX_STALLS) done = true
                } else {
                    stalls = 0
                }
            }

            peaks[bucket] = min(peak, Short.MAX_VALUE.toInt()).toShort()
            if (bucket % 16 == 0) {
                onProgress((bucket + 1).toFloat() / BUCKETS, peaks.copyOf())
            }
        }
        return peaks
    }

    private class Drained {
        var handled = false
        var index = -1
        var offset = 0
        var size = 0
        var presentationTimeUs = 0L
        var hasData = false
        var endOfStream = false
        var isFloatUpdate: Boolean? = null
    }

    /** [scratch] 由呼叫端持有：這是 object singleton，共用可變狀態會在並行擷取時互相踩到。 */
    private fun drain(
        decoder: MediaCodec,
        info: MediaCodec.BufferInfo,
        timeoutUs: Long,
        scratch: Drained,
    ): Drained {
        val result = scratch.apply {
            handled = false; index = -1; offset = 0; size = 0
            presentationTimeUs = 0L; hasData = false; endOfStream = false; isFloatUpdate = null
        }
        val outIndex = decoder.dequeueOutputBuffer(info, timeoutUs)
        when {
            outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                result.handled = true
                val outFormat = decoder.outputFormat
                if (outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    result.isFloatUpdate = outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) ==
                        AudioFormat.ENCODING_PCM_FLOAT
                }
            }

            outIndex >= 0 -> {
                result.handled = true
                result.index = outIndex
                result.offset = info.offset
                result.size = info.size
                result.presentationTimeUs = info.presentationTimeUs
                result.hasData = info.size > 0
                result.endOfStream = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            }
        }
        return result
    }

    /**
     * 循序模式：短檔直接解完，精度完全無損。
     *
     * 迴圈刻意「一次盡量餵滿輸入、一次盡量排空輸出」，讓多個緩衝同時在管線裡跑；
     * 若改成餵一個等一個，每個 frame 都要付一次完整 codec 往返。
     */
    private suspend fun extractSequential(
        decoder: MediaCodec,
        extractor: MediaExtractor,
        durationMs: Long,
        sampleRate: Int,
        channelCount: Int,
        onProgress: (Float, ShortArray) -> Unit,
    ): ShortArray? {
        val totalFrames = max(1L, durationMs * sampleRate / 1000L)
        val accumulator = PeakAccumulator(totalFrames, BUCKETS, channelCount)
        val info = MediaCodec.BufferInfo()
        val scratch = Drained()
        var isFloat = false
        var inputDone = false
        var outputDone = false
        var lastReported = -1

        while (!outputDone) {
            if (!currentCoroutineContext().isActive) return null
            var progressed = false

            while (!inputDone) {
                val inIndex = decoder.dequeueInputBuffer(0)
                if (inIndex < 0) break
                val buffer = decoder.getInputBuffer(inIndex)
                val size = if (buffer == null) -1 else extractor.readSampleData(buffer, 0)
                if (size < 0) {
                    decoder.queueInputBuffer(
                        inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                    )
                    inputDone = true
                } else {
                    decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                    extractor.advance()
                }
                progressed = true
            }

            var drained = drain(decoder, info, 0, scratch)
            if (!drained.handled) drained = drain(decoder, info, IDLE_TIMEOUT_US, scratch)
            if (drained.handled) {
                progressed = true
                drained.isFloatUpdate?.let { isFloat = it }
                if (drained.hasData) {
                    decoder.getOutputBuffer(drained.index)?.let { out ->
                        out.position(drained.offset)
                        out.limit(drained.offset + drained.size)
                        accumulator.feed(out, isFloat)
                    }
                    val percent = (accumulator.framesProcessed * 100 / totalFrames)
                        .toInt().coerceIn(0, 100)
                    if (percent != lastReported) {
                        lastReported = percent
                        onProgress(percent / 100f, accumulator.snapshot())
                    }
                }
                if (drained.index >= 0) decoder.releaseOutputBuffer(drained.index, false)
                if (drained.endOfStream) outputDone = true
            }

            if (!progressed) return accumulator.finish()
        }
        return accumulator.finish()
    }
}
