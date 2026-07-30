package com.wji.meditationplayer

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wji.meditationplayer.data.waveform.WaveformExtractor
import com.wji.meditationplayer.data.waveform.WaveformRepository
import com.wji.meditationplayer.domain.EffectiveTimeline
import com.wji.meditationplayer.domain.Gap
import com.wji.meditationplayer.export.AudioExporter
import com.wji.meditationplayer.playback.FadeController
import com.wji.meditationplayer.playback.MediaSourceBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 在真機/模擬器上驗證單元測試碰不到的部分：真的解碼、真的 player.duration、
 * 真的跨越插入靜默、真的匯出。
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class PlaybackEngineTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun <T> onMain(block: () -> T): T {
        val results = arrayOfNulls<Any?>(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { results[0] = block() }
        @Suppress("UNCHECKED_CAST")
        return results[0] as T
    }

    private fun testFile(name: String, durationMs: Long): Uri {
        val file = TestAudio.writeWav(File(context.cacheDir, name), durationMs)
        return Uri.fromFile(file)
    }

    private fun prepared(uri: Uri, timeline: EffectiveTimeline): ExoPlayer {
        val player = onMain { ExoPlayer.Builder(context).build() }
        val ready = CountDownLatch(1)
        onMain {
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) ready.countDown()
                }
            })
            val source = MediaSourceBuilder.build(context, uri, timeline, MediaItem.fromUri(uri))
            assertNotNull("MediaSource 應該要建得起來", source)
            player.setMediaSource(source!!)
            player.prepare()
        }
        assertTrue("播放器沒有進入 READY", ready.await(30, TimeUnit.SECONDS))
        return player
    }

    @Test
    fun waveformExtractionFollowsTheAmplitudeEnvelope() = runBlocking {
        val uri = testFile("waveform.wav", 20_000)
        var lastProgress = 0f
        var sawPartialWaveform = false
        val peaks = WaveformExtractor.extract(context, uri, 20_000) { progress, partial ->
            lastProgress = progress
            // 漸進顯示：中途就應該拿到已填部分，UI 才畫得出來
            if (progress < 1f && partial.any { it > 0 }) sawPartialWaveform = true
        }
        assertTrue("解碼過程中應回報已填部分的波形", sawPartialWaveform)

        assertNotNull("波形解碼失敗", peaks)
        assertEquals(WaveformExtractor.BUCKETS, peaks!!.size)
        assertEquals("進度應該跑到 100%", 1f, lastProgress, 0.001f)

        // TestAudio 的振幅是每 4 秒 0.9 / 0.25 / 0.7 / 0.1 / 0.85，
        // 所以第 1 段一定明顯大於第 2 段、第 3 段大於第 4 段。
        fun avgOfSecond(from: Int, to: Int): Double {
            val start = WaveformExtractor.BUCKETS * from / 20
            val end = WaveformExtractor.BUCKETS * to / 20
            return (start until end).map { peaks[it].toInt() }.average()
        }
        assertTrue("第 0-4 秒應明顯大於 4-8 秒", avgOfSecond(0, 4) > avgOfSecond(4, 8) * 2)
        assertTrue("第 8-12 秒應明顯大於 12-16 秒", avgOfSecond(8, 12) > avgOfSecond(12, 16) * 2)
    }

    @Test
    fun sampledExtractionStillFollowsTheEnvelope() = runBlocking {
        // 60 秒會走抽樣路徑（512 buckets x 80ms = 41s < 60s），20 秒那個測試走循序路徑，
        // 兩條路徑都要能還原 TestAudio 的 0.9 / 0.25 / 0.7 / 0.1 / 0.85 包絡。
        val uri = testFile("sampled.wav", 60_000)
        val peaks = WaveformExtractor.extract(context, uri, 60_000) { _, _ -> }
        assertNotNull("抽樣擷取失敗", peaks)
        assertEquals(WaveformExtractor.BUCKETS, peaks!!.size)

        fun avgBetween(fromSec: Int, toSec: Int): Double {
            val start = WaveformExtractor.BUCKETS * fromSec / 60
            val end = WaveformExtractor.BUCKETS * toSec / 60
            return (start until end).map { peaks[it].toInt() }.average()
        }
        assertTrue("0-4s 應明顯大於 4-8s", avgBetween(0, 4) > avgBetween(4, 8) * 2)
        assertTrue("8-12s 應明顯大於 12-16s", avgBetween(8, 12) > avgBetween(12, 16) * 2)
        assertTrue("不應有整段為 0 的空洞", peaks.count { it.toInt() == 0 } < peaks.size / 10)
    }

    @Test
    fun waveformCacheServesTheSecondLoadWithoutDecoding() = runBlocking {
        val uri = testFile("cache-probe.wav", 5_000)
        val repository = WaveformRepository(context)
        val fileKey = "cache-probe-key"

        var firstProgressCalls = 0
        val first = repository.load(fileKey, uri, 5_000) { _, _ -> firstProgressCalls++ }
        assertNotNull("第一次擷取失敗", first)
        assertTrue("第一次應該真的有解碼並回報進度", firstProgressCalls > 0)

        var secondProgressCalls = 0
        val second = repository.load(fileKey, uri, 5_000) { _, _ -> secondProgressCalls++ }
        assertNotNull("第二次讀取失敗", second)
        // 命中快取就不會解碼，所以完全不會回報進度
        assertEquals("第二次應命中快取而非重新解碼", 0, secondProgressCalls)
        assertArrayEquals("快取內容必須與原本一致", first, second)
    }

    @Test
    fun playerDurationEqualsInsertedTotalAndIsASingleWindow() {
        val uri = testFile("duration.wav", 20_000)
        val timeline = EffectiveTimeline(
            sourceDurationMs = 20_000,
            gaps = listOf(Gap(5_000, 5_000), Gap(12_000, 3_000)),
            fadeMs = 1_000,
        )
        assertEquals(28_000L, timeline.totalDurationMs)

        val player = prepared(uri, timeline)
        try {
            val duration = onMain { player.duration }
            val windowCount = onMain { player.currentTimeline.windowCount }

            // 這是整個設計的核心主張：插入後的總長直接就是 player.duration。
            assertEquals(
                "player.duration 應等於插入後總長",
                28_000.0,
                duration.toDouble(),
                300.0,
            )
            assertEquals("併接後應該只有一個 window", 1, windowCount)
        } finally {
            onMain { player.release() }
        }
    }

    @Test
    fun playbackCrossesTheSilenceAndResumesTheAudio() {
        val uri = testFile("cross.wav", 12_000)
        // 5 秒處插入 2 秒靜默，總長 14 秒。
        val timeline = EffectiveTimeline(12_000, listOf(Gap(5_000, 2_000)), 500)
        val player = prepared(uri, timeline)
        val fade = onMain { FadeController(player).apply { setTimeline(timeline); start() } }

        try {
            onMain {
                player.seekTo(4_200)
                player.play()
            }
            // 靜默區間是 effective 5000..7000，取樣時抓一次靜默中的音量。
            var sawZeroVolumeInSilence = false
            val deadline = System.currentTimeMillis() + 12_000
            while (System.currentTimeMillis() < deadline) {
                val position = onMain { player.currentPosition }
                val volume = onMain { player.volume }
                if (position in 5_200..6_800 && volume < 0.01f) sawZeroVolumeInSilence = true
                if (position > 7_500) break
                Thread.sleep(100)
            }

            val finalPosition = onMain { player.currentPosition }
            assertTrue(
                "應該要播過 2 秒靜默並繼續播後半段，實際停在 $finalPosition",
                finalPosition > 7_500,
            )
            assertTrue("靜默期間音量應為 0", sawZeroVolumeInSilence)
        } finally {
            onMain {
                fade.stop()
                player.release()
            }
        }
    }

    @Test
    fun exportWritesAFileWithTheInsertedSilence() = runBlocking {
        val uri = testFile("export.wav", 6_000)
        val timeline = EffectiveTimeline(6_000, listOf(Gap(3_000, 2_000)), 500)
        assertEquals(8_000L, timeline.totalDurationMs)

        val output = File(context.cacheDir, "exported.m4a")
        output.delete()

        val ok = AudioExporter(context).export(uri, timeline, output) { }
        assertTrue("匯出失敗", ok)
        assertTrue("輸出檔不存在或為空", output.exists() && output.length() > 0)

        val retriever = MediaMetadataRetriever()
        val exportedDuration = try {
            retriever.setDataSource(output.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
        } finally {
            retriever.release()
        }
        assertEquals(
            "匯出檔的長度應等於插入後總長",
            8_000.0,
            exportedDuration.toDouble(),
            400.0,
        )
    }
}
