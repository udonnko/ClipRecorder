package com.example.cliprecorder.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class VideoQuality(val label: String) {
    HD("HD (720p)"),
    FHD("FHD (1080p)"),
    UHD("4K (2160p)"),
}

enum class Fps(val label: String, val value: Int) {
    FPS_24("24fps", 24),
    FPS_30("30fps", 30),
    FPS_60("60fps", 60),
}

/** 録画秒数 1〜10 秒（旧 enum は削除し Int で保持）*/
const val RECORD_DURATION_MIN = 1
const val RECORD_DURATION_MAX = 10

enum class NamingFormat(val label: String) {
    DATETIME("撮影日時 (例: 20260628_153045)"),
    SEQUENTIAL("連番 (例: clip_001)"),
}

enum class FlashMode { AUTO, ON, OFF }

enum class WhiteBalance(val label: String) {
    AUTO("AUTO"),
    DAYLIGHT("晴天"),
    CLOUDY("曇り"),
    SHADE("日陰"),
    INCANDESCENT("白熱灯"),
    FLUORESCENT("蛍光灯"),
    TWILIGHT("夕暮れ"),
}

enum class AspectRatio(val label: String) {
    RATIO_16_9("16:9"),
    RATIO_4_3("4:3"),
    RATIO_1_1("1:1"),
}

enum class TimelapseInterval(val label: String, val ms: Long) {
    SEC_1("1秒", 1000L),
    SEC_2("2秒", 2000L),
    SEC_5("5秒", 5000L),
}

enum class TimelapseDuration(val label: String, val seconds: Int) {
    SEC_30("30秒", 30),
    MIN_1("1分", 60),
    MIN_5("5分", 300),
}


private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsManager(private val context: Context) {

    companion object {
        private val KEY_QUALITY = stringPreferencesKey("quality")
        private val KEY_FPS = stringPreferencesKey("fps")
        private val KEY_DURATION = stringPreferencesKey("duration")
        private val KEY_NAMING = stringPreferencesKey("naming")
        private val KEY_REAR_CAMERA_ID  = stringPreferencesKey("rear_camera_id")
        private val KEY_DURATION_SEC    = stringPreferencesKey("duration_sec")
        private val KEY_FLASH_MODE      = stringPreferencesKey("flash_mode")
        private val KEY_GPS_ENABLED     = booleanPreferencesKey("gps_enabled")
        private val KEY_COUNTDOWN       = booleanPreferencesKey("countdown")
        private val KEY_GRID_ENABLED    = booleanPreferencesKey("grid_enabled")
        private val KEY_STABILIZATION   = booleanPreferencesKey("stabilization")
        private val KEY_BURST_COUNT         = stringPreferencesKey("burst_count")
        private val KEY_TL_INTERVAL         = stringPreferencesKey("tl_interval")
        private val KEY_TL_DURATION         = stringPreferencesKey("tl_duration")
        private val KEY_AUTO_DELETE_MERGED  = booleanPreferencesKey("auto_delete_merged")
        private val KEY_LEVEL_ENABLED       = booleanPreferencesKey("level_enabled")
        // カメラボタンのズーム倍率手動オーバーライド（例: "0:0.6,1:1.0,2:3.0"）
        private val KEY_CAMERA_ZOOM_OVERRIDES = stringPreferencesKey("camera_zoom_overrides")
        private val KEY_ASPECT_RATIO = stringPreferencesKey("aspect_ratio")
        private val KEY_FILE_PREFIX = stringPreferencesKey("file_prefix")
        // 動画再生に使うアプリのパッケージ名（空文字=毎回選択）
        val KEY_VIDEO_PLAYER_PACKAGE = stringPreferencesKey("video_player_package")
    }

    val quality: Flow<VideoQuality> = context.dataStore.data.map { prefs ->
        runCatching { VideoQuality.valueOf(prefs[KEY_QUALITY] ?: "") }.getOrDefault(VideoQuality.FHD)
    }

    val fps: Flow<Fps> = context.dataStore.data.map { prefs ->
        runCatching { Fps.valueOf(prefs[KEY_FPS] ?: "") }.getOrDefault(Fps.FPS_30)
    }

    /** 録画秒数（1〜10 秒）。無料版は常に 1 秒固定 */
    val recordDurationSec: Flow<Int> = if (com.example.cliprecorder.BuildConfig.IS_FREE_TIER) {
        kotlinx.coroutines.flow.flowOf(RECORD_DURATION_MIN)
    } else {
        context.dataStore.data.map { prefs ->
            prefs[KEY_DURATION_SEC]?.toIntOrNull()?.coerceIn(RECORD_DURATION_MIN, RECORD_DURATION_MAX)
                ?: RECORD_DURATION_MIN
        }
    }

    val namingFormat: Flow<NamingFormat> = context.dataStore.data.map { prefs ->
        runCatching { NamingFormat.valueOf(prefs[KEY_NAMING] ?: "") }.getOrDefault(NamingFormat.DATETIME)
    }

    // null = 自動（CameraX デフォルトのリアカメラ）
    val rearCameraId: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_REAR_CAMERA_ID]?.takeIf { it.isNotEmpty() }
    }

    suspend fun setQuality(q: VideoQuality) { context.dataStore.edit { it[KEY_QUALITY] = q.name } }
    suspend fun setFps(f: Fps) { context.dataStore.edit { it[KEY_FPS] = f.name } }
    suspend fun setRecordDurationSec(sec: Int) {
        context.dataStore.edit { it[KEY_DURATION_SEC] = sec.coerceIn(RECORD_DURATION_MIN, RECORD_DURATION_MAX).toString() }
    }
    suspend fun setNamingFormat(f: NamingFormat) { context.dataStore.edit { it[KEY_NAMING] = f.name } }
    suspend fun setRearCameraId(id: String?) {
        context.dataStore.edit {
            if (id != null) it[KEY_REAR_CAMERA_ID] = id else it.remove(KEY_REAR_CAMERA_ID)
        }
    }

    val flashMode: Flow<FlashMode> = context.dataStore.data.map { prefs ->
        runCatching { FlashMode.valueOf(prefs[KEY_FLASH_MODE] ?: "") }.getOrDefault(FlashMode.AUTO)
    }

    suspend fun setFlashMode(mode: FlashMode) {
        context.dataStore.edit { it[KEY_FLASH_MODE] = mode.name }
    }

    val gpsEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_GPS_ENABLED] ?: false
    }

    suspend fun setGpsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_GPS_ENABLED] = enabled }
    }

    val countdownEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_COUNTDOWN] ?: false
    }

    suspend fun setCountdownEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_COUNTDOWN] = enabled }
    }

    val gridEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_GRID_ENABLED] ?: false
    }

    suspend fun setGridEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_GRID_ENABLED] = enabled }
    }

    val stabilizationEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_STABILIZATION] ?: true
    }

    suspend fun setStabilizationEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_STABILIZATION] = enabled }
    }

    val burstCount: Flow<Int> = if (com.example.cliprecorder.BuildConfig.IS_FREE_TIER) {
        kotlinx.coroutines.flow.flowOf(1)
    } else {
        context.dataStore.data.map { prefs ->
            prefs[KEY_BURST_COUNT]?.toIntOrNull()?.coerceIn(1, 10) ?: 1
        }
    }

    suspend fun setBurstCount(count: Int) {
        context.dataStore.edit { it[KEY_BURST_COUNT] = count.coerceIn(1, 10).toString() }
    }

    val timelapseInterval: Flow<TimelapseInterval> = context.dataStore.data.map { prefs ->
        runCatching { TimelapseInterval.valueOf(prefs[KEY_TL_INTERVAL] ?: "") }
            .getOrDefault(TimelapseInterval.SEC_2)
    }

    val timelapseDuration: Flow<TimelapseDuration> = context.dataStore.data.map { prefs ->
        runCatching { TimelapseDuration.valueOf(prefs[KEY_TL_DURATION] ?: "") }
            .getOrDefault(TimelapseDuration.MIN_1)
    }

    suspend fun setTimelapseInterval(v: TimelapseInterval) {
        context.dataStore.edit { it[KEY_TL_INTERVAL] = v.name }
    }

    suspend fun setTimelapseDuration(v: TimelapseDuration) {
        context.dataStore.edit { it[KEY_TL_DURATION] = v.name }
    }

    val levelEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_LEVEL_ENABLED] ?: false
    }

    suspend fun setLevelEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_LEVEL_ENABLED] = enabled }
    }

    val autoDeleteAfterMerge: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_AUTO_DELETE_MERGED] ?: false
    }

    suspend fun setAutoDeleteAfterMerge(enabled: Boolean) {
        context.dataStore.edit { it[KEY_AUTO_DELETE_MERGED] = enabled }
    }

    /** カメラボタンのズーム倍率手動オーバーライド（index → zoom）*/
    val cameraZoomOverrides: Flow<Map<Int, Float>> = context.dataStore.data.map { prefs ->
        val str = prefs[KEY_CAMERA_ZOOM_OVERRIDES] ?: return@map emptyMap()
        str.split(",").mapNotNull { entry ->
            val parts = entry.split(":")
            if (parts.size == 2) {
                val idx = parts[0].toIntOrNull() ?: return@mapNotNull null
                val zoom = parts[1].toFloatOrNull() ?: return@mapNotNull null
                idx to zoom
            } else null
        }.toMap()
    }

    suspend fun setCameraZoomOverride(index: Int, zoom: Float) {
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_CAMERA_ZOOM_OVERRIDES] ?: ""
            val map = parseCameraZoomOverrides(current).toMutableMap()
            map[index] = zoom
            prefs[KEY_CAMERA_ZOOM_OVERRIDES] = map.entries.joinToString(",") { "${it.key}:${it.value}" }
        }
    }

    suspend fun clearCameraZoomOverrides() {
        context.dataStore.edit { it.remove(KEY_CAMERA_ZOOM_OVERRIDES) }
    }

    private fun parseCameraZoomOverrides(str: String): Map<Int, Float> =
        str.split(",").mapNotNull { entry ->
            val parts = entry.split(":")
            if (parts.size == 2) {
                val idx = parts[0].toIntOrNull() ?: return@mapNotNull null
                val zoom = parts[1].toFloatOrNull() ?: return@mapNotNull null
                idx to zoom
            } else null
        }.toMap()

    val aspectRatio: Flow<AspectRatio> = context.dataStore.data.map { prefs ->
        runCatching { AspectRatio.valueOf(prefs[KEY_ASPECT_RATIO] ?: "") }.getOrDefault(AspectRatio.RATIO_16_9)
    }

    suspend fun setAspectRatio(ratio: AspectRatio) {
        context.dataStore.edit { it[KEY_ASPECT_RATIO] = ratio.name }
    }

    val fileNamePrefix: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_FILE_PREFIX] ?: "VID_"
    }

    suspend fun setFileNamePrefix(prefix: String) {
        context.dataStore.edit { it[KEY_FILE_PREFIX] = prefix }
    }

    val videoPlayerPackage: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_VIDEO_PLAYER_PACKAGE] ?: ""
    }

    suspend fun setVideoPlayerPackage(pkg: String) {
        context.dataStore.edit {
            if (pkg.isEmpty()) it.remove(KEY_VIDEO_PLAYER_PACKAGE)
            else it[KEY_VIDEO_PLAYER_PACKAGE] = pkg
        }
    }
}
