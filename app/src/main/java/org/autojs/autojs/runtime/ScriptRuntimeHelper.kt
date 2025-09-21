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
        // 修复：使用正确的方法获取 Context
        val context = runtime.uiHandler.context
        return HealthConnect(context, runtime)
    }
}
