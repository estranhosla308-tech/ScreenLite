package br.com.screenlite.recorder

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Process
import android.view.Surface
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Grava tela (H.264) + áudio (AAC) direto com MediaCodec/MediaMuxer.
 * Áudio: sistema (AudioPlaybackCapture), microfone, ou os dois misturados.
 */
class SessionRecorder(
    private val projection: MediaProjection,
    private val fd: FileDescriptor,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val useSystemAudio: Boolean,
    private val useMic: Boolean
) {
    companion object {
        private const val SAMPLE_RATE = 44100
        private const val AUDIO_BITRATE = 128_000
        private const val FRAMES = 1024
    }

    private val lock = Any()
    private var muxer: MediaMuxer? = null
    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var sysRecord: AudioRecord? = null
    private var micRecord: AudioRecord? = null
    var inputSurface: Surface? = null
        private set

    private var videoTrack = -1
    private var audioTrack = -1
    private var tracksExpected = 1
    private var tracksAdded = 0
    @Volatile private var muxerStarted = false
    @Volatile private var running = false
    private var released = false
    private var baseUs = 0L
    private var lastVideoPts = -1L
    private var lastAudioPts = -1L
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    // ---------- preparo ----------

    fun prepare() {
        muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val vc = createVideoCodec()
        videoCodec = vc
        inputSurface = vc.createInputSurface()
        vc.start()

        if (useSystemAudio) sysRecord = buildSystemRecord()
        if (useMic) micRecord = buildMicRecord()
        if (sysRecord != null || micRecord != null) {
            val afmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            val ac = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            ac.configure(afmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            ac.start()
            audioCodec = ac
            tracksExpected = 2
        }
        baseUs = System.nanoTime() / 1000
    }

    private fun createVideoCodec(): MediaCodec {
        fun make(high: Boolean): MediaCodec {
            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                if (high) setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
            }
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            try {
                c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (e: Exception) {
                c.release(); throw e
            }
            return c
        }
        return try { make(true) } catch (e: Exception) { make(false) }
    }

    @SuppressLint("MissingPermission")
    private fun buildSystemRecord(): AudioRecord {
        val cfg = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord.Builder()
            .setAudioFormat(fmt)
            .setBufferSizeInBytes(maxOf(min * 4, 32768))
            .setAudioPlaybackCaptureConfig(cfg)
            .build()
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release(); throw IllegalStateException("Não foi possível capturar o áudio do sistema")
        }
        return rec
    }

    @SuppressLint("MissingPermission")
    private fun buildMicRecord(): AudioRecord {
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 4, 16384))
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release(); throw IllegalStateException("Não foi possível acessar o microfone")
        }
        return rec
    }

    // ---------- controle ----------

    fun start() {
        running = true
        videoThread = Thread({ drainVideo() }, "sl-video").also { it.start() }
        if (audioCodec != null) audioThread = Thread({ audioLoop() }, "sl-audio").also { it.start() }
    }

    @Synchronized
    fun stop() {
        if (released) return
        released = true
        running = false
        try { videoCodec?.signalEndOfInputStream() } catch (_: Exception) { }
        try { audioThread?.join(3000) } catch (_: Exception) { }
        try { videoThread?.join(3000) } catch (_: Exception) { }
        try { sysRecord?.stop() } catch (_: Exception) { }
        try { sysRecord?.release() } catch (_: Exception) { }
        try { micRecord?.stop() } catch (_: Exception) { }
        try { micRecord?.release() } catch (_: Exception) { }
        try { videoCodec?.stop() } catch (_: Exception) { }
        try { videoCodec?.release() } catch (_: Exception) { }
        try { audioCodec?.stop() } catch (_: Exception) { }
        try { audioCodec?.release() } catch (_: Exception) { }
        try { inputSurface?.release() } catch (_: Exception) { }
        synchronized(lock) {
            try { if (muxerStarted) muxer?.stop() } catch (_: Exception) { }
            try { muxer?.release() } catch (_: Exception) { }
            muxerStarted = false
        }
    }

    // ---------- muxer ----------

    private fun addTrack(format: MediaFormat, video: Boolean) {
        synchronized(lock) {
            val m = muxer ?: return
            if (video) videoTrack = m.addTrack(format) else audioTrack = m.addTrack(format)
            tracksAdded++
            if (tracksAdded == tracksExpected) { m.start(); muxerStarted = true }
        }
    }

    private fun writeSample(video: Boolean, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        while (!muxerStarted && running) { try { Thread.sleep(5) } catch (_: Exception) { } }
        if (!muxerStarted) return
        synchronized(lock) {
            val m = muxer ?: return
            var pts = info.presentationTimeUs - baseUs
            if (pts < 0) pts = 0
            val last = if (video) lastVideoPts else lastAudioPts
            if (pts <= last) pts = last + 1
            if (video) lastVideoPts = pts else lastAudioPts = pts
            info.presentationTimeUs = pts
            buf.position(info.offset)
            buf.limit(info.offset + info.size)
            m.writeSampleData(if (video) videoTrack else audioTrack, buf, info)
        }
    }

    // ---------- vídeo ----------

    private fun drainVideo() {
        val codec = videoCodec ?: return
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, 10_000)
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    addTrack(codec.outputFormat, true)
                } else if (idx >= 0) {
                    val buf = codec.getOutputBuffer(idx)
                    if (buf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        writeSample(true, buf, info)
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }
        } catch (_: Exception) { }
    }

    // ---------- áudio ----------

    private fun readFully(rec: AudioRecord, buf: ShortArray): Boolean {
        var off = 0
        while (off < buf.size && running) {
            val n = rec.read(buf, off, buf.size - off, AudioRecord.READ_BLOCKING)
            if (n <= 0) return false
            off += n
        }
        return off == buf.size
    }

    private fun clip(v: Int): Short = (if (v > 32767) 32767 else if (v < -32768) -32768 else v).toShort()

    private fun audioLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val codec = audioCodec ?: return
        val info = MediaCodec.BufferInfo()
        val sysBuf = ShortArray(FRAMES * 2)
        val micBuf = ShortArray(FRAMES)
        val mix = ShortArray(FRAMES * 2)
        var total = 0L
        var startUs = -1L
        try {
            sysRecord?.startRecording()
            micRecord?.startRecording()
            while (running) {
                val sys = sysRecord
                val mic = micRecord
                val gotSys = sys != null && readFully(sys, sysBuf)
                val gotMic = mic != null && readFully(mic, micBuf)
                if (!gotSys && !gotMic) {
                    if (!running) break
                    Thread.sleep(5); continue
                }
                for (i in 0 until FRAMES) {
                    var l = 0; var r = 0
                    if (gotSys) { l += sysBuf[2 * i]; r += sysBuf[2 * i + 1] }
                    if (gotMic) { l += micBuf[i]; r += micBuf[i] }
                    mix[2 * i] = clip(l); mix[2 * i + 1] = clip(r)
                }
                if (startUs < 0) startUs = System.nanoTime() / 1000 - FRAMES * 1_000_000L / SAMPLE_RATE
                val pts = startUs + total * 1_000_000L / SAMPLE_RATE
                total += FRAMES
                drainAudio(codec, info, 0, false)
                val idx = codec.dequeueInputBuffer(10_000)
                if (idx >= 0) {
                    val ib = codec.getInputBuffer(idx)
                    if (ib != null) {
                        ib.clear()
                        ib.order(ByteOrder.LITTLE_ENDIAN)
                        ib.asShortBuffer().put(mix)
                        codec.queueInputBuffer(idx, 0, mix.size * 2, pts, 0)
                    }
                }
            }
            val eosIdx = codec.dequeueInputBuffer(200_000)
            if (eosIdx >= 0) {
                val eosPts = (if (startUs < 0) System.nanoTime() / 1000 else startUs) + total * 1_000_000L / SAMPLE_RATE
                codec.queueInputBuffer(eosIdx, 0, 0, eosPts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            drainAudio(codec, info, 200_000, true)
        } catch (_: Exception) { }
    }

    private fun drainAudio(codec: MediaCodec, info: MediaCodec.BufferInfo, timeoutUs: Long, untilEos: Boolean) {
        var tries = 0
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, timeoutUs)
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                addTrack(codec.outputFormat, false)
            } else if (idx >= 0) {
                val buf = codec.getOutputBuffer(idx)
                if (buf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    writeSample(false, buf, info)
                }
                codec.releaseOutputBuffer(idx, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
            } else if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!untilEos) return
                tries++
                if (tries > 10) return
            }
        }
    }
}
