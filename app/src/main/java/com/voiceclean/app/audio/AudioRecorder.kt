package com.voiceclean.app.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

const val SAMPLE_RATE = 16_000

/** Anything that produces 16 kHz mono float PCM chunks (lets tests/fakes replace the microphone). */
fun interface AudioSource {
    fun chunks(): Flow<FloatArray>
}

class MicPermissionException : SecurityException("Microphone permission not granted")
class AudioInitException(msg: String) : IllegalStateException(msg)

/**
 * Cold flow over the microphone: collecting starts an AudioRecord (VOICE_RECOGNITION, 16 kHz mono PCM16),
 * cancelling the collector stops and releases it. Chunks are [chunkMs] long, scaled to [-1, 1].
 */
class AudioRecorder(private val context: Context, private val chunkMs: Int = 100) : AudioSource {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // checked in hasPermission()
    override fun chunks(): Flow<FloatArray> = flow {
        if (!hasPermission()) throw MicPermissionException()
        val chunkSamples = SAMPLE_RATE * chunkMs / 1000
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) throw AudioInitException("Audio input not available (min buffer $minBuf)")
        val bufBytes = maxOf(minBuf, chunkSamples * 2 * 4)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes,
        )
        try {
            if (rec.state != AudioRecord.STATE_INITIALIZED) throw AudioInitException("Could not initialise the microphone")
            rec.startRecording()
            val shorts = ShortArray(chunkSamples)
            while (true) {
                currentCoroutineContext().ensureActive()
                var read = 0
                while (read < chunkSamples) {
                    val n = rec.read(shorts, read, chunkSamples - read)
                    if (n < 0) throw AudioInitException("Microphone read failed ($n)")
                    read += n
                }
                emit(FloatArray(chunkSamples) { shorts[it] / 32768f })
            }
        } finally {
            runCatching { if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) rec.stop() }
            rec.release()
        }
    }.flowOn(Dispatchers.IO)
}
