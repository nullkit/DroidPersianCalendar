package com.byagowi.persiancalendar.ui.athan

import android.app.KeyguardManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import com.byagowi.persiancalendar.DEFAULT_ATHAN_VOLUME
import com.byagowi.persiancalendar.FAJR_KEY
import com.byagowi.persiancalendar.KEY_EXTRA_PRAYER
import com.byagowi.persiancalendar.R
import com.byagowi.persiancalendar.utils.FIVE_SECONDS_IN_MILLIS
import com.byagowi.persiancalendar.utils.TEN_SECONDS_IN_MILLIS
import com.byagowi.persiancalendar.utils.THIRTY_SECONDS_IN_MILLIS
import com.byagowi.persiancalendar.utils.applyAppLanguage
import com.byagowi.persiancalendar.utils.athanVolume
import com.byagowi.persiancalendar.utils.getCustomAthanUri
import com.byagowi.persiancalendar.utils.getRawUri
import com.byagowi.persiancalendar.utils.isAscendingAthanVolumeEnabled
import com.byagowi.persiancalendar.utils.logException
import java.util.concurrent.TimeUnit

class AthanActivity : ComponentActivity() {

    private val ascendingVolumeStep = 6
    private var currentVolumeSteps = 1
    private val handler = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null
    private var mediaPlayer: MediaPlayer? = null
    private var alreadyStopped = false
    private var spentSeconds = 0
    private var originalVolume = -1
    private val stopTask = object : Runnable {
        override fun run() = runCatching {
            spentSeconds += 5
            if ((ringtone == null && mediaPlayer == null) ||
                ringtone?.isPlaying == false ||
                mediaPlayer?.isPlaying == false ||
                spentSeconds > 360 ||
                (stopAtHalfMinute && spentSeconds > 30)
            ) finish() else handler.postDelayed(this, FIVE_SECONDS_IN_MILLIS)
        }.onFailure(logException).onFailure { finish() }.let {}
    }
    private var stopAtHalfMinute = false

    private val ascendVolume = object : Runnable {
        override fun run() {
            currentVolumeSteps++
            getSystemService<AudioManager>()
                ?.setStreamVolume(AudioManager.STREAM_ALARM, currentVolumeSteps, 0)
            handler.postDelayed(this, TimeUnit.SECONDS.toMillis(ascendingVolumeStep.toLong()))
            if (currentVolumeSteps == 10) handler.removeCallbacks(this)
        }
    }

    private var phoneStateListener: PhoneStateListener? = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, incomingNumber: String) {
            if (state == TelephonyManager.CALL_STATE_RINGING || state == TelephonyManager.CALL_STATE_OFFHOOK) {
                stop()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (originalVolume != -1) getSystemService<AudioManager>()
            ?.setStreamVolume(AudioManager.STREAM_ALARM, originalVolume, 0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prayerKey = intent.getStringExtra(KEY_EXTRA_PRAYER) ?: ""
        val isFajr = prayerKey == FAJR_KEY
        var goMute = false

        getSystemService<AudioManager>()?.let { audioManager ->
            originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            if (athanVolume != DEFAULT_ATHAN_VOLUME) // Don't change alarm volume if isn't set in-app
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, athanVolume, 0)
            // Mute if system alarm is set to lowest, ringer mode is silent/vibration and it isn't Fajr
            if (originalVolume == 1 && !isFajr &&
                audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL
            ) goMute = true
        }

        val customAthanUri = getCustomAthanUri(this)
        if (!goMute) runCatching {
            if (customAthanUri != null) {
                runCatching {
                    MediaPlayer.create(this, customAthanUri).duration // is in milliseconds
                }.onFailure(logException).onSuccess {
                    // if the URIs duration is less than half a minute, it is probably a looping one
                    // so stop on half a minute regardless
                    if (it < THIRTY_SECONDS_IN_MILLIS) stopAtHalfMinute = true
                }
                ringtone = RingtoneManager.getRingtone(this, customAthanUri).also {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        it.audioAttributes = AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .build()
                    } else {
                        @Suppress("DEPRECATION")
                        it.streamType = AudioManager.STREAM_ALARM
                    }
                    volumeControlStream = AudioManager.STREAM_ALARM
                    it.play()
                }
            } else {
                mediaPlayer = MediaPlayer().also { mediaPlayer ->
                    runCatching {
                        val rawUri = resources.getRawUri(R.raw.special).toUri()
                        mediaPlayer.setDataSource(this, rawUri)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            mediaPlayer.setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_ALARM)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                    .build()
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            mediaPlayer.setAudioStreamType(AudioManager.STREAM_ALARM)
                        }
                        volumeControlStream = AudioManager.STREAM_ALARM
                        mediaPlayer.prepare()
                    }.onFailure(logException)
                    mediaPlayer.start()
                }
            }
        }.onFailure(logException)

        applyAppLanguage(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            getSystemService<KeyguardManager>()?.requestDismissKeyguard(this, null)
            window.addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
            )
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        setAthanActivityContent(this, prayerKey, onClick = ::stop)

        handler.postDelayed(stopTask, TEN_SECONDS_IN_MILLIS)

        if (isAscendingAthanVolumeEnabled) handler.post(ascendVolume)

        runCatching {
            getSystemService<TelephonyManager>()?.listen(
                phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE
            )
        }.onFailure(logException)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) stop()
    }

    override fun onBackPressed() = stop()

    private fun stop() {
        if (alreadyStopped) return
        alreadyStopped = true

        runCatching {
            getSystemService<TelephonyManager>()?.listen(
                phoneStateListener, PhoneStateListener.LISTEN_NONE
            )
            phoneStateListener = null
        }.onFailure(logException)

        ringtone?.stop()

        runCatching {
            mediaPlayer?.also {
                if (it.isPlaying) {
                    it.stop()
                    it.release()
                }
            }
        }.onFailure(logException)

        handler.removeCallbacks(stopTask)
        if (isAscendingAthanVolumeEnabled) handler.removeCallbacks(ascendVolume)
        finish()
    }
}
