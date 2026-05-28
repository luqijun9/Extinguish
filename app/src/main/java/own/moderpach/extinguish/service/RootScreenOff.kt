package own.moderpach.extinguish.service

import android.annotation.SuppressLint
import android.os.Build
import android.os.IBinder
import java.lang.reflect.Method

object RootScreenOff {

    @SuppressLint("BlockedPrivateApi", "PrivateApi")
    @JvmStatic
    fun main(args: Array<String>) {
        val mode = when (args.firstOrNull()) {
            "on", "1", "2" -> 2
            else -> 0
        }

        val token = getDisplayToken()
        val surfaceControlClass = Class.forName("android.view.SurfaceControl")
        val setPowerMode = surfaceControlClass.getMethod(
            "setDisplayPowerMode", IBinder::class.java, Int::class.javaPrimitiveType
        )
        setPowerMode.invoke(null, token, mode)

        if (mode == 0 || mode == 1) {
            kotlin.system.exitProcess(0)
        }
    }

    private fun getDisplayToken(): IBinder {
        if (Build.VERSION.SDK_INT >= 34) {
            return getTokenViaDisplayControl()
        }
        val surfaceControlClass = Class.forName("android.view.SurfaceControl")
        return try {
            val method: Method = surfaceControlClass.getMethod("getInternalDisplayToken")
            method.invoke(null) as IBinder
        } catch (_: NoSuchMethodException) {
            val method: Method = surfaceControlClass.getMethod("getBuiltInDisplay", Int::class.javaPrimitiveType)
            method.invoke(null, 0) as IBinder
        }
    }

    @SuppressLint("BlockedPrivateApi", "PrivateApi")
    private fun getTokenViaDisplayControl(): IBinder {
        val factoryClass = Class.forName("com.android.internal.os.ClassLoaderFactory")
        val createLoader = factoryClass.getDeclaredMethod(
            "createClassLoader",
            String::class.java, String::class.java, String::class.java,
            ClassLoader::class.java, Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType, String::class.java
        )
        val classLoader = createLoader.invoke(
            null, "/system/framework/services.jar", null, null,
            ClassLoader.getSystemClassLoader(), 0, true, null
        ) as ClassLoader

        val displayControlClass = classLoader.loadClass("com.android.server.display.DisplayControl")
        val loadLibrary = Runtime::class.java.getDeclaredMethod(
            "loadLibrary0", Class::class.java, String::class.java
        ).apply { isAccessible = true }
        loadLibrary.invoke(Runtime.getRuntime(), displayControlClass, "android_servers")

        val ids = displayControlClass.getMethod("getPhysicalDisplayIds").invoke(null) as LongArray
        return displayControlClass.getMethod(
            "getPhysicalDisplayToken", Long::class.javaPrimitiveType
        ).invoke(null, ids[0]) as IBinder
    }
}
