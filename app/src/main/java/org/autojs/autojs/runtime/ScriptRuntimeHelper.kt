package org.autojs.autojs.runtime

import org.autojs.autojs.runtime.api.HealthConnect
import com.stardust.autojs.runtime.ScriptRuntime

/**
 * ScriptRuntime 扩展帮助类
 */
object ScriptRuntimeHelper {
    
    /**
     * 为 ScriptRuntime 添加 HealthConnect API
     */
    @JvmStatic
    fun addHealthConnect(runtime: ScriptRuntime): HealthConnect {
        // Fix: Use proper context access method for ScriptRuntime
        val context = runtime.uiHandler.applicationContext
        return HealthConnect(context, runtime)
    }
}
