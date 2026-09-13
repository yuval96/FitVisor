package com.example.fitvisor__demo.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log

enum class AudioFeedbackEvent(val resourceName: String) {
    REP_CORRECT("rep_correct"),
    REP_INCORRECT("rep_incorrect"),
    WORKOUT_COMPLETE("workout_complete")
}

interface AudioFeedback : AutoCloseable {
    fun play(event: AudioFeedbackEvent)
    override fun close()
}

/**
 * Reusable, asynchronous player for short workout sounds. Audio files are
 * resolved by name so the app still builds before the final files are placed in
 * res/raw; adding them later requires no Kotlin changes.
 */
class AudioFeedbackManager(context: Context) : AudioFeedback {
    private val lock = Any()
    private val soundPool = SoundPool.Builder()
        .setMaxStreams(AudioFeedbackEvent.values().size)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
    private val soundIds = mutableMapOf<AudioFeedbackEvent, Int>()
    private val loadedSoundIds = mutableSetOf<Int>()
    private val pendingPlays = mutableMapOf<Int, Int>()
    private var released = false

    init {
        soundPool.setOnLoadCompleteListener { pool, soundId, status ->
            val queuedPlays = synchronized(lock) {
                if (released || status != LOAD_SUCCESS) {
                    pendingPlays.remove(soundId)
                    0
                } else {
                    loadedSoundIds.add(soundId)
                    pendingPlays.remove(soundId) ?: 0
                }
            }
            repeat(queuedPlays) { playLoaded(pool, soundId) }
        }

        for (event in AudioFeedbackEvent.values()) {
            val resourceId = context.resources.getIdentifier(
                event.resourceName,
                RAW_RESOURCE_TYPE,
                context.packageName
            )
            if (resourceId == 0) {
                Log.i(TAG, "Optional audio resource res/raw/${event.resourceName} was not found")
            } else {
                soundIds[event] = soundPool.load(context.applicationContext, resourceId, LOAD_PRIORITY)
            }
        }
    }

    override fun play(event: AudioFeedbackEvent) {
        val soundId = soundIds[event] ?: return
        val playNow = synchronized(lock) {
            if (released) return
            if (soundId in loadedSoundIds) {
                true
            } else {
                pendingPlays[soundId] = (pendingPlays[soundId] ?: 0) + 1
                false
            }
        }
        if (playNow) playLoaded(soundPool, soundId)
    }

    override fun close() {
        synchronized(lock) {
            if (released) return
            released = true
            soundIds.clear()
            loadedSoundIds.clear()
            pendingPlays.clear()
        }
        soundPool.release()
    }

    private fun playLoaded(pool: SoundPool, soundId: Int) {
        synchronized(lock) {
            if (released) return
            pool.play(soundId, VOLUME, VOLUME, PLAY_PRIORITY, NO_LOOP, NORMAL_RATE)
        }
    }

    private companion object {
        const val TAG = "AudioFeedback"
        const val RAW_RESOURCE_TYPE = "raw"
        const val LOAD_PRIORITY = 1
        const val LOAD_SUCCESS = 0
        const val VOLUME = 1f
        const val PLAY_PRIORITY = 1
        const val NO_LOOP = 0
        const val NORMAL_RATE = 1f
    }
}
