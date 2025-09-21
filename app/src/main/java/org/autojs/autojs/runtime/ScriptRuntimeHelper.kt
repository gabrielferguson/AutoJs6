package org.autojs.autojs.runtime

import com.stardust.autojs.runtime.ScriptRuntime
import org.autojs.autojs.runtime.api.HealthConnect

/**
 * ScriptRuntime 扩展帮助类
 * 用于添加新的 API 到 ScriptRuntime
 */
object ScriptRuntimeHelper {
    
    /**
     * 为 ScriptRuntime 添加 HealthConnect API
     */
    @JvmStatic
    fun addHealthConnect(runtime: ScriptRuntime): HealthConnect {
        val context = runtime.uiHandler.context
        return HealthConnect(context, runtime)
    }
}
