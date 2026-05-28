package own.moderpach.extinguish.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import extinguish.shizuku_service.DisplayControlService
import extinguish.shizuku_service.IDisplayControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import own.moderpach.extinguish.BuildConfig
import own.moderpach.extinguish.MainActivity
import own.moderpach.extinguish.R
import rikka.shizuku.Shizuku

private const val TAG = "QuickScreenOffService"

class QuickScreenOffService : Service() {

    companion object {
        const val EXTRA_SCREEN = "screen"
        const val EXTRA_TIMER = "timer"
        const val SCREEN_ON = 0
        const val SCREEN_OFF = 1

        private const val NOTIFICATION_ID = 9001
        private const val CHANNEL_ID = "quick_screen_off"
        private const val SHIZUKU_TIMEOUT_MS = 15_000L
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var displayControl: IDisplayControl? = null
    private var pendingAction = -1
    private var timerSeconds = 0
    private var timerJob: kotlinx.coroutines.Job? = null

    private var keepAwakeView: View? = null
    private var keepAwakeParams: WindowManager.LayoutParams? = null

    private val args = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, DisplayControlService::class.java.name)
    )
        .processNameSuffix("quick_off")
        .tag("quick_off")
        .debuggable(false)
        .version(BuildConfig.VERSION_CODE)
        .daemon(true)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && Shizuku.pingBinder()) {
                displayControl = IDisplayControl.Stub.asInterface(binder)
                Log.d(TAG, "Shizuku service connected")
            }
            executeAction()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            displayControl = null
            Log.d(TAG, "Shizuku service disconnected")
        }
    }

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        val notification = baseNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        bindShizukuService()
        scope.launch {
            delay(SHIZUKU_TIMEOUT_MS)
            if (displayControl == null) {
                Log.w(TAG, "Shizuku connection timeout after 15s, retrying once")
                bindShizukuService()
                delay(5000)
                if (displayControl == null) {
                    Log.w(TAG, "Shizuku connection failed")
                    stopSelfAndCleanup()
                }
            }
        }
    }

    private fun bindShizukuService() {
        try {
            if (!Shizuku.pingBinder()) return
            Shizuku.bindUserService(args, connection)
        } catch (e: Exception) {
            Log.e(TAG, "bindUserService failed: $e")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelfAndCleanup()
            return START_NOT_STICKY
        }
        pendingAction = when (intent.getIntExtra(EXTRA_SCREEN, -1)) {
            SCREEN_ON -> DisplayControlService.POWER_MODE_NORMAL
            SCREEN_OFF -> DisplayControlService.POWER_MODE_OFF
            else -> {
                stopSelfAndCleanup()
                return START_NOT_STICKY
            }
        }
        timerSeconds = intent.getIntExtra(EXTRA_TIMER, 0)
        if (displayControl != null) {
            executeAction()
        }
        return START_NOT_STICKY
    }

    private fun executeAction() {
        val action = pendingAction.also { pendingAction = -1 }
        if (action < 0) return

        displayControl?.setPowerModeToSurfaceControl(action)
        Log.d(TAG, "setPowerModeToSurfaceControl: $action")

        if (timerSeconds > 0) {
            startTimer(action)
        } else {
            stopSelfAndCleanup()
        }
    }

    private fun startTimer(currentAction: Int) {
        val reverseAction = if (currentAction == DisplayControlService.POWER_MODE_OFF)
            DisplayControlService.POWER_MODE_NORMAL
        else
            DisplayControlService.POWER_MODE_OFF

        addKeepAwakeWindow()

        timerJob?.cancel()
        timerJob = scope.launch {
            var remaining = timerSeconds
            while (remaining > 0 && isActive) {
                notifyTimer(remaining)
                delay(1000)
                remaining--
            }
            if (isActive) {
                displayControl?.setPowerModeToSurfaceControl(reverseAction)
                Log.d(TAG, "timer fired, reverse to: $reverseAction")
            }
            stopSelfAndCleanup()
        }
    }

    private fun addKeepAwakeWindow() {
        if (keepAwakeView != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        keepAwakeView = View(this)
        keepAwakeParams = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.RGBA_8888
            gravity = Gravity.START or Gravity.TOP
            width = 1
            height = 1
            x = 0; y = 0
            flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        }
        try {
            wm.addView(keepAwakeView, keepAwakeParams)
            Log.d(TAG, "keep-awake window added")
        } catch (e: Exception) {
            Log.w(TAG, "keep-awake window failed: $e")
        }
    }

    private fun removeKeepAwakeWindow() {
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            keepAwakeView?.let { wm.removeView(it) }
        } catch (_: Exception) {}
        keepAwakeView = null
        keepAwakeParams = null
    }

    private fun baseNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.extinguish_24px)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(getString(R.string.app_name))
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        .setOngoing(false)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .build()

    private fun notifyTimer(remainingSeconds: Int) {
        val mins = remainingSeconds / 60
        val secs = remainingSeconds % 60
        val text = if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.extinguish_24px)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("定时 $text 后恢复")
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "定时灭屏", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
    }

    private fun stopSelfAndCleanup() {
        timerJob?.cancel()
        removeKeepAwakeWindow()
        stopForeground(STOP_FOREGROUND_REMOVE)
        scope.cancel()
        stopSelf()
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        timerJob?.cancel()
        scope.cancel()
        removeKeepAwakeWindow()
        try {
            Shizuku.unbindUserService(args, connection, false)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
