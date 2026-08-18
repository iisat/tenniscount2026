package com.tenniscount.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.tenniscount.app.MainActivity
import com.tenniscount.app.R
import com.tenniscount.app.util.AppLog

/**
 * Foreground service типа «microphone»: держит процесс живым и сохраняет
 * доступ к микрофону при выключенном/заблокированном экране. Само
 * распознавание живёт в [ListeningController]; сервис показывает постоянное
 * уведомление с текущим счётом и кнопками «Пауза/Продолжить» и «Стоп».
 */
class ListeningService : Service() {

    private var paused = false
    private var scoreText: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLog.i(TAG, "onStartCommand: action=${intent?.action ?: "START"}")
        if (intent == null) {
            // Рестарт после убийства процесса: распознавание не восстановлено,
            // показывать «фантомное» уведомление о прослушивании нельзя.
            AppLog.w(TAG, "onStartCommand: рестарт без интента — останавливаемся")
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_STOP -> {
                ListeningController.get(this).onStopRequested?.invoke()
                stopSelf()
            }
            ACTION_PAUSE_TOGGLE -> {
                paused = !paused
                ListeningController.get(this).onPauseToggleRequested?.invoke()
                refreshNotification()
            }
            ACTION_UPDATE -> {
                scoreText = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                paused = intent.getBooleanExtra(EXTRA_PAUSED, paused)
                refreshNotification()
            }
            else -> {
                scoreText = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                startForegroundWithNotification()
            }
        }
        // NOT_STICKY: после убийства процесса распознавание всё равно
        // не восстановится (ListeningController пересоздаётся в OFF), поэтому
        // сервис не должен воскресать сам — пользователь запустит его заново.
        return START_NOT_STICKY
    }

    private fun startForegroundWithNotification() {
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            },
        )
    }

    private fun refreshNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val pauseToggle = PendingIntent.getService(
            this, 1,
            Intent(this, ListeningService::class.java).setAction(ACTION_PAUSE_TOGGLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, ListeningService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(scoreText)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .addAction(
                0,
                getString(if (paused) R.string.resume else R.string.pause),
                pauseToggle,
            )
            .addAction(0, getString(R.string.listen_stop), stop)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "ListeningService"
        private const val CHANNEL_ID = "listening"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.tenniscount.app.action.STOP"
        private const val ACTION_PAUSE_TOGGLE = "com.tenniscount.app.action.PAUSE_TOGGLE"
        private const val ACTION_UPDATE = "com.tenniscount.app.action.UPDATE"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_PAUSED = "paused"

        fun startIntent(context: Context, scoreText: String): Intent =
            Intent(context, ListeningService::class.java).putExtra(EXTRA_TEXT, scoreText)

        fun stopIntent(context: Context): Intent =
            Intent(context, ListeningService::class.java).setAction(ACTION_STOP)

        fun updateIntent(context: Context, scoreText: String, paused: Boolean): Intent =
            Intent(context, ListeningService::class.java)
                .setAction(ACTION_UPDATE)
                .putExtra(EXTRA_TEXT, scoreText)
                .putExtra(EXTRA_PAUSED, paused)
    }
}
