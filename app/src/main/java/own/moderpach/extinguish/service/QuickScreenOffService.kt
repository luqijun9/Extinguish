package own.moderpach.extinguish.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import extinguish.ipc.result.EventResult
import extinguish.shizuku_service.DisplayControlService
import extinguish.shizuku_service.EventsProviderService
import extinguish.shizuku_service.IDisplayControl
import extinguish.shizuku_service.IEventsListener
import extinguish.shizuku_service.IEventsProvider
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
        const val EXTRA_VOLKEY = "volkey"
        const val EXTRA_CANCEL = "cancel"
        const val SCREEN_ON = 0
        const val SCREEN_OFF = 1

        private const val NOTIFICATION_ID = 9001
        private const val CHANNEL_ID = "quick_screen_off"
        private const val SHIZUKU_TIMEOUT_MS = 15_000L
        private const val VOLUME_KEY_FILTER = "-F -e \": 0001 0072\" -e \": 0001 0073\""
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var displayControl: IDisplayControl? = null
    private var eventsProvider: IEventsProvider? = null
    private var pendingAction = -1
    private var timerSeconds = 0
    private var timerJob: kotlinx.coroutines.Job? = null

    private var keepAwakeView: View? = null
    private var keepAwakeParams: WindowManager.LayoutParams? = null
    private var screenReceiverRegistered = false
    private var timerCancelled = false
    private var volumeKeyRegistered = false
    private var volkeyEnabled = false
    private var currentScreenMode = DisplayControlService.POWER_MODE_NORMAL

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_USER_PRESENT) {
                Log.d(TAG, "screen unlocked, cancelling timer")
                cancelTimerDueToUnlock()
            }
        }
    }

    private val volumeKeyListener = object : IEventsListener.Stub() {
        override fun onEvent(event: EventResult) {
            if (timerCancelled) return
            val v0 = event.v0 ?: ""
            val v1 = event.v1 ?: ""
            val v2 = event.v2 ?: ""
            if (v0 == "0001" && (v1 == "0072" || v1 == "0073") && v2 == "00000000") {
                val isVolumeUp = v1 == "0073"
                Log.d(TAG, "volume key pressed (up=$isVolumeUp), toggling screen")
                if (isVolumeUp) {
                    toggleScreenAndCancel()
                } else {
                    toggleScreenByVolumeKey()
                }
            }
        }
    }

    private val displayControlArgs = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, DisplayControlService::class.java.name)
    )
        .processNameSuffix("quick_off")
        .tag("quick_off")
        .debuggable(false)
        .version(BuildConfig.VERSION_CODE)
        .daemon(true)

    private val eventsProviderArgs = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, EventsProviderService::class.java.name)
    )
        .processNameSuffix("quick_off_events")
        .tag("quick_off_events")
        .debuggable(false)
        .version(BuildConfig.VERSION_CODE)
        .daemon(true)

    private val displayConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && Shizuku.pingBinder()) {
                displayControl = IDisplayControl.Stub.asInterface(binder)
                Log.d(TAG, "displayControl connected")
            }
            executeAction()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            displayControl = null
            Log.d(TAG, "displayControl disconnected")
        }
    }

    private val eventsConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && Shizuku.pingBinder()) {
                eventsProvider = IEventsProvider.Stub.asInterface(binder)
                Log.d(TAG, "eventsProvider connected")
            }
            tryStartVolumeKey()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            eventsProvider = null
            volumeKeyRegistered = false
            Log.d(TAG, "eventsProvider disconnected")
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
            Shizuku.bindUserService(displayControlArgs, displayConnection)
        } catch (e: Exception) {
            Log.e(TAG, "bindUserService failed: $e")
        }
    }

    private fun bindEventsServiceIfNeeded() {
        if (!volkeyEnabled) return
        try {
            if (!Shizuku.pingBinder()) return
            Shizuku.bindUserService(eventsProviderArgs, eventsConnection)
        } catch (e: Exception) {
            Log.e(TAG, "bindEventsService failed: $e")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelfAndCleanup()
            return START_NOT_STICKY
        }
        if (intent.getIntExtra(EXTRA_CANCEL, 0) == 1) {
            Log.d(TAG, "cancel requested from notification")
            if (!timerCancelled) {
                timerCancelled = true
                displayControl?.setPowerModeToSurfaceControl(DisplayControlService.POWER_MODE_NORMAL)
                notifyTimerCancelled()
                timerJob?.cancel()
                timerJob = null
            }
            scope.launch {
                delay(5000)
                stopSelfAndCleanup()
            }
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
        volkeyEnabled = intent.getIntExtra(EXTRA_VOLKEY, 0) == 1
        if (volkeyEnabled) {
            bindEventsServiceIfNeeded()
        }
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
        registerScreenReceiver()
        tryStartVolumeKey()

        currentScreenMode = currentAction

        notifyTimer(timerSeconds)

        timerJob?.cancel()
        timerJob = scope.launch {
            delay(1000)
            var remaining = timerSeconds - 1
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

    private fun tryStartVolumeKey() {
        if (!volkeyEnabled) return
        val provider = eventsProvider ?: return
        if (volumeKeyRegistered) return
        volumeKeyRegistered = true
        try {
            provider.registerListener(volumeKeyListener)
            provider.launch(VOLUME_KEY_FILTER)
            Log.d(TAG, "volume key listener registered")
        } catch (e: Exception) {
            Log.w(TAG, "volume key register failed: $e")
        }
    }

    private fun stopVolumeKey() {
        val provider = eventsProvider
        if (volumeKeyRegistered) {
            try {
                provider?.unregisterListener(volumeKeyListener)
                provider?.stop()
            } catch (_: Exception) {}
            volumeKeyRegistered = false
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

    private fun registerScreenReceiver() {
        if (screenReceiverRegistered) return
        screenReceiverRegistered = true
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_USER_PRESENT))
        Log.d(TAG, "screen receiver registered")
    }

    private fun unregisterScreenReceiver() {
        if (!screenReceiverRegistered) return
        screenReceiverRegistered = false
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {}
    }

    private fun cancelTimerDueToUnlock() {
        if (timerCancelled) return
        timerCancelled = true
        timerJob?.cancel()
        timerJob = null
        displayControl?.setPowerModeToSurfaceControl(DisplayControlService.POWER_MODE_NORMAL)
        notifyTimerCancelled()
        scope.launch {
            delay(5000)
            stopSelfAndCleanup()
        }
    }

    private fun toggleScreenAndCancel() {
        if (timerCancelled) return
        displayControl?.setPowerModeToSurfaceControl(DisplayControlService.POWER_MODE_NORMAL)
        Log.d(TAG, "volume up: turned screen on and cancelling timer")
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            am.adjustVolume(AudioManager.ADJUST_LOWER, 0)
            Log.d(TAG, "volume down to offset volume up key press")
        } catch (e: Exception) {
            Log.w(TAG, "failed to adjust volume: $e")
        }
        timerCancelled = true
        timerJob?.cancel()
        timerJob = null
        notifyTimerCancelled()
        scope.launch {
            delay(5000)
            stopSelfAndCleanup()
        }
    }

    private fun toggleScreenByVolumeKey() {
        if (timerCancelled) return
        if (currentScreenMode == DisplayControlService.POWER_MODE_OFF) {
            currentScreenMode = DisplayControlService.POWER_MODE_NORMAL
        } else {
            currentScreenMode = DisplayControlService.POWER_MODE_OFF
        }
        displayControl?.setPowerModeToSurfaceControl(currentScreenMode)
        Log.d(TAG, "volume down toggled screen to: $currentScreenMode")
    }

    private fun notifyTimerCancelled() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.extinguish_24px)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("定时已取消，服务即将关闭")
                .setOngoing(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        )
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
        if (timerCancelled) return
        val mins = remainingSeconds / 60
        val secs = remainingSeconds % 60
        val text = if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
        val hint = if (volkeyEnabled) {
            if (currentScreenMode == DisplayControlService.POWER_MODE_OFF) "（按音量键亮屏）"
            else "（屏幕已亮，按音量键灭屏）"
        } else ""
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.extinguish_24px)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("定时 $text 后恢复$hint")
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .addAction(
                android.R.drawable.ic_delete,
                "取消",
                PendingIntent.getService(
                    this, 1,
                    Intent(this, QuickScreenOffService::class.java).putExtra(EXTRA_CANCEL, 1),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, builder.build())
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
        stopVolumeKey()
        unregisterScreenReceiver()
        removeKeepAwakeWindow()
        stopForeground(STOP_FOREGROUND_REMOVE)
        scope.cancel()
        stopSelf()
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        timerJob?.cancel()
        scope.cancel()
        stopVolumeKey()
        unregisterScreenReceiver()
        removeKeepAwakeWindow()
        try {
            Shizuku.unbindUserService(displayControlArgs, displayConnection, false)
            if (volkeyEnabled) {
                Shizuku.unbindUserService(eventsProviderArgs, eventsConnection, false)
            }
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
