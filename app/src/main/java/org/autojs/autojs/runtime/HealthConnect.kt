package org.autojs.autojs.runtime.api

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SleepSessionRecord.Stage
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.runBlocking
import org.autojs.autojs.annotation.ScriptInterface
import org.autojs.autojs.extension.ArrayExtensions.toNativeArray
import org.autojs.autojs.extension.ArrayExtensions.toNativeObject
import org.autojs.autojs.runtime.ScriptRuntime
import org.autojs.autojs.util.RhinoUtils.coerceString
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.max

/**
 * Health Connect API 包装器
 * 为 AutoJs6 脚本提供睡眠数据读写功能
 *
 * 变更要点：
 * - 支持 13 位毫秒时间戳（startTimeMillis / endTimeMillis），兼容原有字符串时间
 * - 支持 stages 列表写入/读取（毫秒级）
 * - 支持用户自定义 metadata 记录方式（manual/auto/active/unknown）与设备类型
 * - 读取返回同时包含毫秒时间戳与可读字符串
 */
class HealthConnect(private val context: Context, private val scriptRuntime: ScriptRuntime) {

    companion object {
        private const val TAG = "HealthConnect"
        private const val HEALTH_CONNECT_PACKAGE = "com.google.android.apps.healthdata"

        // Health Connect 权限
        private val REQUIRED_PERMISSIONS = setOf(
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getWritePermission(SleepSessionRecord::class)
        )

        private val STAGE_NAME_TO_CONST = mapOf(
            "UNKNOWN" to SleepSessionRecord.STAGE_TYPE_UNKNOWN,
            "AWAKE" to SleepSessionRecord.STAGE_TYPE_AWAKE,
            "SLEEPING" to SleepSessionRecord.STAGE_TYPE_SLEEPING,
            "OUT_OF_BED" to SleepSessionRecord.STAGE_TYPE_OUT_OF_BED,
            "AWAKE_IN_BED" to SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
            "LIGHT" to SleepSessionRecord.STAGE_TYPE_LIGHT,
            "DEEP" to SleepSessionRecord.STAGE_TYPE_DEEP,
            "REM" to SleepSessionRecord.STAGE_TYPE_REM
        )

        private val STAGE_CONST_TO_NAME = STAGE_NAME_TO_CONST.entries.associate { it.value to it.key }

        private val DEVICE_NAME_TO_TYPE = mapOf(
            "UNKNOWN" to Device.TYPE_UNKNOWN,
            "WATCH" to Device.TYPE_WATCH,
            "PHONE" to Device.TYPE_PHONE,
            "SCALE" to Device.TYPE_SCALE,
            "RING" to Device.TYPE_RING,
            "HEAD_MOUNTED" to Device.TYPE_HEAD_MOUNTED,
            "FITNESS_BAND" to Device.TYPE_FITNESS_BAND,
            "CHEST_STRAP" to Device.TYPE_CHEST_STRAP,
            "SMART_DISPLAY" to Device.TYPE_SMART_DISPLAY
        )
    }

    private var healthConnectClient: HealthConnectClient? = null

    init {
        initializeHealthConnect()
    }

    /**
     * 初始化 Health Connect 客户端
     */
    private fun initializeHealthConnect() {
        try {
            when (HealthConnectClient.getSdkStatus(context)) {
                HealthConnectClient.SDK_AVAILABLE -> {
                    healthConnectClient = HealthConnectClient.getOrCreate(context)
                    Log.d(TAG, "Health Connect client initialized successfully")
                }
                HealthConnectClient.SDK_UNAVAILABLE -> {
                    Log.w(TAG, "Health Connect SDK is unavailable")
                }
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                    Log.w(TAG, "Health Connect SDK requires provider update")
                }
                else -> {
                    Log.w(TAG, "Health Connect SDK status unknown")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Health Connect", e)
        }
    }

    /**
     * 检查 Health Connect 是否可用
     */
    @ScriptInterface
    fun isAvailable(): Boolean {
        return try {
            HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
        } catch (e: Exception) {
            Log.e(TAG, "Error checking Health Connect availability", e)
            false
        }
    }

    /**
     * 检查是否已授予所需权限
     */
    @ScriptInterface
    fun hasPermissions(): Boolean {
        val client = healthConnectClient ?: return false
        return try {
            runBlocking {
                val grantedPermissions = client.permissionController.getGrantedPermissions()
                REQUIRED_PERMISSIONS.all { it in grantedPermissions }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking permissions", e)
            false
        }
    }

    /**
     * 打开 Health Connect 权限页（或设置页兜底）
     */
    @ScriptInterface
    fun requestPermissions() {
        if (!isAvailable()) error("Health Connect is not available on this device")
        try {
            val intent = Intent(Intent.ACTION_VIEW_PERMISSION_USAGE)
                .setPackage(HEALTH_CONNECT_PACKAGE)
                .putExtra(Intent.EXTRA_PACKAGE_NAME, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open Health Connect permissions", e)
            openHealthConnectSettings()
        }
    }

    /**
     * 打开 Health Connect 设置页面
     */
    @ScriptInterface
    fun openHealthConnectSettings() {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(HEALTH_CONNECT_PACKAGE)
                ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$HEALTH_CONNECT_PACKAGE"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open Health Connect settings", e)
            throw RuntimeException("无法打开 Health Connect 设置页面", e)
        }
    }

    // ========= ③ 写入逻辑：支持 13 位毫秒、stages、metadata =========
    @ScriptInterface
    fun writeSleepRecord(sleepData: Any): Boolean {
        if (!isAvailable()) error("Health Connect is not available")
        if (!hasPermissions()) error("Health Connect permissions not granted. Call requestPermissions() first.")
        val client = healthConnectClient ?: error("Health Connect client not initialized")

        return try {
            val map = anyToMap(sleepData) // ← 兼容 Rhino NativeObject/Map
            val startTime = toInstantMs(map["startTime"] ?: error("startTime required"))
            val endTime = toInstantMs(map["endTime"] ?: error("endTime required"))
            if (!endTime.isAfter(startTime)) error("endTime must be after startTime")

            val stages = buildStages(map["stages"])

            val zone = ZoneId.systemDefault()
            val startOffset = zone.rules.getOffset(startTime)
            val endOffset = zone.rules.getOffset(endTime)

            val record = SleepSessionRecord(
                startTime = startTime,
                startZoneOffset = startOffset,
                endTime = endTime,
                endZoneOffset = endOffset,
                title = optString(map["title"]),
                notes = optString(map["notes"]),                     // ← 允许为 null
                stages = stages,
                metadata = buildMetadata(map["metadata"])            // ← 内部也做了安全处理
            )

            runBlocking { client.insertRecords(listOf(record)) }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write sleep record", e)
            throw RuntimeException("写入睡眠数据失败: ${e.message}", e)
        }
    }

    // ========= ④ 读取逻辑：输出 recordingMethod/Device，移除旧属性读取 =========
    @ScriptInterface
    @JvmOverloads
    fun getSleepRecords(days: Int = 7): NativeArray {
        if (!isAvailable()) error("Health Connect is not available")
        if (!hasPermissions()) error("Health Connect permissions not granted. Call requestPermissions() first.")
        val client = healthConnectClient ?: error("Health Connect client not initialized")

        return try {
            runBlocking {
                val endTime = Instant.now()
                val startTime = endTime.minusSeconds(days * 24 * 3600L)
                val response = client.readRecords(
                    ReadRecordsRequest(
                        recordType = SleepSessionRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
                    )
                )

                val list = response.records.map { r ->
                    val meta = r.metadata
                    val device = meta.device
                    val obj = mutableMapOf<String, Any?>(
                        "id" to meta.id,
                        "clientRecordId" to meta.clientRecordId,
                        "clientRecordVersion" to meta.clientRecordVersion,
                        "recordingMethod" to when (meta.recordingMethod) {
                            Metadata.RECORDING_METHOD_MANUAL_ENTRY -> "manual"
                            Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED -> "auto"
                            Metadata.RECORDING_METHOD_ACTIVELY_RECORDED -> "active"
                            else -> "unknown"
                        },
                        "device" to if (device == null) null else mapOf(
                            "type" to device.type,
                            "manufacturer" to (device.manufacturer ?: ""),
                            "model" to (device.model ?: "")
                        ).toNativeObject(),
                        "dataOriginPackage" to meta.dataOrigin.packageName,
                        "lastModifiedTime" to formatInstant(meta.lastModifiedTime),
                        "startTime" to formatInstant(r.startTime),
                        "endTime" to formatInstant(r.endTime),
                        "duration" to (r.endTime.epochSecond - r.startTime.epochSecond),
                        "title" to (r.title ?: ""),
                        "notes" to (r.notes ?: "")
                    )

                    if (r.stages.isNotEmpty()) {
                        val stages = r.stages.map { s ->
                            mapOf(
                                "stage" to s.stage,
                                "startTime" to formatInstant(s.startTime),
                                "endTime" to formatInstant(s.endTime)
                            ).toNativeObject()
                        }
                        obj["stages"] = stages.toNativeArray()
                    }

                    obj.toNativeObject()
                }

                list.toNativeArray()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read sleep records", e)
            throw RuntimeException("读取睡眠数据失败: ${e.message}", e)
        }
    }

    /**
     * 删除睡眠记录（按 ID）
     */
    @ScriptInterface
    fun deleteSleepRecord(recordId: String): Boolean {
        if (!isAvailable()) error("Health Connect is not available")
        if (!hasPermissions()) error("Health Connect permissions not granted")
        val client = healthConnectClient ?: error("Health Connect client not initialized")

        return try {
            runBlocking {
                client.deleteRecords(
                    recordType = SleepSessionRecord::class,
                    recordIdsList = listOf(recordId),
                    clientRecordIdsList = emptyList<String>()
                )
                Log.d(TAG, "Sleep record deleted successfully: $recordId")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete sleep record", e)
            throw RuntimeException("删除睡眠数据失败: ${e.message}", e)
        }
    }

    /**
     * 获取睡眠统计信息（保留原逻辑，duration 改为使用毫秒时间戳计算更稳妥）
     */
    @ScriptInterface
    fun getSleepStats(days: Int = 7): NativeObject {
        val records = getSleepRecords(days)
        val stats = mutableMapOf<String, Any?>()
        if (records.size == 0) {
            stats["totalRecords"] = 0
            stats["averageDuration"] = 0
            stats["totalDuration"] = 0
            return stats.toNativeObject()
        }
        var totalDuration = 0L
        var minDuration = Long.MAX_VALUE
        var maxDuration = 0L
        for (i in 0 until records.size) {
            val record = records[i] as NativeObject
            val dur = (record["endTimeMillis"] as? Long ?: 0L) - (record["startTimeMillis"] as? Long ?: 0L)
            totalDuration += max(0L, dur)
            minDuration = minOf(minDuration, max(0L, dur))
            maxDuration = maxOf(maxDuration, max(0L, dur))
        }
        stats["totalRecords"] = records.size
        stats["totalDuration"] = totalDuration / 1000 // 秒
        stats["averageDuration"] = (totalDuration / records.size) / 1000
        stats["minDuration"] = minDuration / 1000
        stats["maxDuration"] = maxDuration / 1000
        stats["averageHours"] = (totalDuration / records.size) / 1000.0 / 3600.0
        return stats.toNativeObject()
    }

    // ========= ① 时间戳+stage 解析工具 =========
    private fun toInstantMs(any: Any?): Instant = when (any) {
        is Number -> Instant.ofEpochMilli(any.toLong())
        is String -> {
            if (any.matches(Regex("^\\d{13}$"))) Instant.ofEpochMilli(any.toLong())
            else parseDateTime(any, ZoneId.systemDefault())
        }
        else -> throw IllegalArgumentException("time must be 13-digit millis, number, or ISO string")
    }

    private fun toStageType(value: Any?): Int {
        // 允许传对象：{type:"light"} 或 {value:"deep"} 或 {stage:"rem"}
        if (value is Map<*, *>) {
            val inner = value["type"] ?: value["value"] ?: value["stage"]
            return toStageType(inner)
        }
        val s = coerceString(value).trim().lowercase()
        return when (s) {
            "unknown" -> SleepSessionRecord.STAGE_TYPE_UNKNOWN
            "awake" -> SleepSessionRecord.STAGE_TYPE_AWAKE
            "sleeping", "asleep", "general" -> SleepSessionRecord.STAGE_TYPE_SLEEPING
            "out_of_bed", "out-of-bed", "outofbed" -> SleepSessionRecord.STAGE_TYPE_OUT_OF_BED
            "awake_in_bed", "awake-in-bed", "awakeinbed" -> SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED
            "light" -> SleepSessionRecord.STAGE_TYPE_LIGHT
            "deep" -> SleepSessionRecord.STAGE_TYPE_DEEP
            "rem" -> SleepSessionRecord.STAGE_TYPE_REM
            else -> (value as? Number)?.toInt() ?: SleepSessionRecord.STAGE_TYPE_UNKNOWN
        }
    }

    private fun buildStages(list: Any?): List<SleepSessionRecord.Stage> {
        val items = (list as? Iterable<*>) ?: return emptyList()
        return items.map { it as Map<*, *> }.map { m ->
            val start = toInstantMs(m["startTime"])
            val end = toInstantMs(m["endTime"])
            require(end.isAfter(start)) { "stage.endTime must be after stage.startTime" }
            SleepSessionRecord.Stage(
                startTime = start,
                endTime = end,
                stage = toStageType(m["stage"]) // <== 确保是 Int
            )
        }
    }

    // ========= ② Metadata 构建（支持用户控制操作类型/设备/ID） =========
    private fun buildDevice(map: Any?): Device? {
        val m = map as? Map<*, *> ?: return null
        val type = (m["type"] as? Number)?.toInt() ?: Device.TYPE_PHONE
        val manufacturer = optString(m["manufacturer"])
        val model = optString(m["model"])
        return Device(type = type, manufacturer = manufacturer, model = model)
    }

    private fun buildMetadata(meta: Any?): Metadata {
        val m = meta as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val method = optString(m["recordingMethod"])?.lowercase() ?: "manual"
        val id = optString(m["id"])
        val clientId = optString(m["clientRecordId"])
        val clientVer = (m["clientRecordVersion"] as? Number)?.toLong() ?: 0L
        val device = buildDevice(m["device"])

        fun manual(): Metadata =
            when {
                id != null -> Metadata.manualEntryWithId(id, device)
                clientId != null -> Metadata.manualEntry(clientId, clientVer, device)
                else -> Metadata.manualEntry(device)
            }

        fun auto(): Metadata {
            val dev = device ?: Device(Device.TYPE_UNKNOWN)
            return when {
                id != null -> Metadata.autoRecordedWithId(id, dev)
                clientId != null -> Metadata.autoRecorded(dev, clientId, clientVer) // ← 设备优先
                else -> Metadata.autoRecorded(dev)
            }
        }

        fun active(): Metadata {
            val dev = device ?: Device(Device.TYPE_UNKNOWN)
            return when {
                id != null -> Metadata.activelyRecordedWithId(id, dev)
                clientId != null -> Metadata.activelyRecorded(dev, clientId, clientVer) // ← 设备优先
                else -> Metadata.activelyRecorded(dev)
            }
        }

        fun unknown(): Metadata =
            when {
                id != null -> Metadata.unknownRecordingMethodWithId(id, device)
                clientId != null -> Metadata.unknownRecordingMethod(clientId, clientVer, device)
                else -> Metadata.unknownRecordingMethod(device)
            }

        return when (method) {
            "manual", "manual_entry" -> manual()
            "auto", "automatically_recorded", "automatically" -> auto()
            "active", "actively_recorded" -> active()
            "unknown" -> unknown()
            else -> manual()
        }
    }

    // 可选字符串：为 null/空白则返回 null；如果来自 Rhino 的 NativeObject，也安全处理。
    private fun optString(v: Any?): String? = when (v) {
        null -> null
        is String -> v.takeIf { it.isNotBlank() }
        is NativeObject -> org.autojs.autojs.util.RhinoUtils.coerceString(v).takeIf { it.isNotBlank() }
        else -> null
    }

    // -------------------- 工具方法 --------------------

    private fun parseReadWindow(options: Any?): Pair<Instant, Instant> {
        val map = anyToMap(options)
        val now = Instant.now()
        val end = (map["endTimeMillis"] as? Number)?.toLong()?.let { Instant.ofEpochMilli(it) } ?: now
        val start = (map["startTimeMillis"] as? Number)?.toLong()?.let { Instant.ofEpochMilli(it) }
            ?: run {
                val days = (map["days"] as? Number)?.toInt() ?: 7
                end.minusSeconds(days * 24 * 3600L)
            }
        return start to end
    }

    private fun formatInstant(instant: Instant?): String {
        if (instant == null) return ""
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    }

    private fun parseAnyToInstant(value: Any?, zoneId: ZoneId, name: String): Instant {
        require(value != null) { "$name is required" }
        return when (value) {
            is Number -> Instant.ofEpochMilli(value.toLong())
            is String -> {
                val v = value.trim()
                // 纯数字字符串，支持 13 位毫秒或 10 位秒
                if (v.matches(Regex("\\d{13}"))) {
                    Instant.ofEpochMilli(v.toLong())
                } else if (v.matches(Regex("\\d{10}"))) {
                    Instant.ofEpochSecond(v.toLong())
                } else {
                    parseDateTime(v, zoneId) // 兼容旧格式
                }
            }
            is NativeObject -> parseAnyToInstant(coerceString(value), zoneId, name)
            else -> error("Unsupported $name format: $value")
        }
    }

    private fun parseDateTime(dateTimeStr: String, zoneId: ZoneId): Instant {
        return try {
            when {
                dateTimeStr.contains("T") -> java.time.OffsetDateTime.parse(dateTimeStr).toInstant()
                dateTimeStr.matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")) ->
                    LocalDateTime.parse(dateTimeStr, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                        .atZone(zoneId).toInstant()
                dateTimeStr.matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}")) ->
                    LocalDateTime.parse("$dateTimeStr:00", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                        .atZone(zoneId).toInstant()
                else -> error("Unsupported date format: $dateTimeStr")
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid date format: $dateTimeStr. Use epochMillis(13位) 或 'yyyy-MM-dd HH:mm:ss' / ISO 格式", e)
        }
    }

    private fun parseStages(
        input: Any?,
        zoneId: ZoneId,
        sessionStart: Instant,
        sessionEnd: Instant
    ): List<Stage> {
        if (input == null) return emptyList()
        val list = when (input) {
            is NativeArray -> (0 until input.length).mapNotNull { input[it] }.toList()
            is List<*> -> input
            else -> error("stages must be an array")
        }

        return list.mapIndexed { idx, it ->
            val m = anyToMap(it)
            val s = parseAnyToInstant(m["startTimeMillis"] ?: m["startTime"], zoneId, "stages[$idx].startTime")
            val e = parseAnyToInstant(m["endTimeMillis"] ?: m["endTime"], zoneId, "stages[$idx].endTime")
            require(e.isAfter(s)) { "stages[$idx] endTime must be after startTime" }

            // 阶段支持字符串或数值常量
            val stageConst = when (val st = m["stage"]) {
                is Number -> st.toInt()
                is String -> {
                    val key = st.trim().uppercase()
                    STAGE_NAME_TO_CONST[key] ?: error("Unknown stage: $st")
                }
                else -> error("stages[$idx].stage is required")
            }

            // 可选：校验在会话范围内
            require(!s.isBefore(sessionStart) && !e.isAfter(sessionEnd)) {
                "stages[$idx] must be within session window"
            }

            Stage(
                startTime = s,
                endTime = e,
                stage = stageConst
            )
        }
    }

    private fun deviceTypeName(type: Int?): String {
        return when (type) {
            Device.TYPE_UNKNOWN -> "UNKNOWN"
            Device.TYPE_WATCH -> "WATCH"
            Device.TYPE_PHONE -> "PHONE"
            Device.TYPE_SCALE -> "SCALE"
            Device.TYPE_RING -> "RING"
            Device.TYPE_HEAD_MOUNTED -> "HEAD_MOUNTED"
            Device.TYPE_FITNESS_BAND -> "FITNESS_BAND"
            Device.TYPE_CHEST_STRAP -> "CHEST_STRAP"
            Device.TYPE_SMART_DISPLAY -> "SMART_DISPLAY"
            else -> ""
        }
    }

    private fun recordingMethodName(metadata: Metadata): String = when (metadata.recordingMethod) {
        Metadata.RECORDING_METHOD_MANUAL_ENTRY -> "MANUAL_ENTRY"
        Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED -> "AUTOMATICALLY_RECORDED"
        Metadata.RECORDING_METHOD_ACTIVELY_RECORDED -> "ACTIVELY_RECORDED"
        else -> "UNKNOWN"
    }

    @Suppress("UNCHECKED_CAST")
    private fun anyToMap(any: Any?): Map<String, Any?> {
        if (any == null) return emptyMap()
        return when (any) {
            is Map<*, *> -> any as Map<String, Any?>
            is NativeObject -> {
                val map = mutableMapOf<String, Any?>()
                any.ids.forEach { id ->
                    map[id.toString()] = any.get(id.toString(), any)
                }
                map
            }
            else -> emptyMap()
        }
    }
}
