package org.autojs.autojs

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import org.autojs.autojs.ui.BaseActivity
import org.autojs.autojs.util.ViewUtils
import org.autojs.autojs6.R
import org.autojs.autojs6.databinding.ActivityPermissionsRationaleBinding

/**
 * Activity to show Health Connect permissions rationale.
 * This activity handles the Health Connect permissions explanation and 
 * allows users to navigate to Health Connect settings.
 * 
 * Handles Android Health Connect integration for AutoJs6:
 * - Shows rationale for health data permissions
 * - Provides navigation to Health Connect settings
 * - Handles Health Connect availability detection
 * - Supports both ACTION_SHOW_PERMISSIONS_RATIONALE and VIEW_PERMISSION_USAGE intents
 * 
 * Created for Health Connect SDK integration.
 */
class PermissionsRationaleActivity : BaseActivity() {

    private lateinit var binding: ActivityPermissionsRationaleBinding

    companion object {
        private const val TAG = "PermissionsRationale"
        private const val HEALTH_CONNECT_PACKAGE = "com.google.android.apps.healthdata"
        private const val HEALTH_CONNECT_ACTION = "androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE"
        private const val VIEW_PERMISSION_USAGE_ACTION = "android.intent.action.VIEW_PERMISSION_USAGE"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityPermissionsRationaleBinding.inflate(layoutInflater).also {
            setContentView(it.root)
            
            // Set up toolbar
            it.toolbar.apply {
                setTitle(R.string.text_health_connect_permissions)
                setSupportActionBar(this)
                setNavigationOnClickListener { finish() }
            }
        }
        
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        
        // Check Health Connect availability and update UI accordingly
        checkHealthConnectAvailability()
        
        setupUI()
        
        // Log the intent that launched this activity for debugging
        Log.d(TAG, "Activity launched with action: ${intent?.action}")
    }

    private fun checkHealthConnectAvailability() {
        val isHealthConnectAvailable = isHealthConnectAvailable()
        
        binding.apply {
            if (!isHealthConnectAvailable) {
                // Show a warning that Health Connect is not available
                textDescription.text = getString(R.string.text_health_connect_not_available_description)
                buttonGoToSettings.text = getString(R.string.text_install_health_connect)
            }
        }
        
        Log.d(TAG, "Health Connect available: $isHealthConnectAvailable")
    }

    private fun setupUI() {
        binding.apply {
            // Set up button click listeners
            buttonGoToSettings.setOnClickListener {
                if (isHealthConnectAvailable()) {
                    openHealthConnectSettings()
                } else {
                    openHealthConnectInstallPage()
                }
            }
            
            buttonNotNow.setOnClickListener {
                finish()
            }
        }
    }

    private fun openHealthConnectSettings() {
        try {
            // Primary approach: Try to open Health Connect app directly
            val healthConnectIntent = packageManager.getLaunchIntentForPackage(HEALTH_CONNECT_PACKAGE)
            if (healthConnectIntent != null) {
                startActivity(healthConnectIntent)
                showToast(R.string.text_opening_health_connect)
                finish()
                return
            }
            
            // Fallback 1: Try to open Health Connect app settings page
            val appSettingsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", HEALTH_CONNECT_PACKAGE, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(appSettingsIntent)
            showToast(R.string.text_opening_health_connect_settings)
            
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open Health Connect settings", e)
            
            // Fallback 2: Try to open system permissions or general settings
            try {
                val fallbackIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS)
                } else {
                    Intent(Settings.ACTION_SETTINGS)
                }
                startActivity(fallbackIntent)
                showToast(R.string.text_opening_system_settings)
            } catch (fallbackException: Exception) {
                Log.e(TAG, "Failed to open any settings", fallbackException)
                showToast(R.string.text_unable_to_open_settings)
            }
        }
        
        // Close this activity after trying to open settings
        finish()
    }

    private fun openHealthConnectInstallPage() {
        try {
            // Try to open Google Play Store page for Health Connect
            val playStoreIntent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("market://details?id=$HEALTH_CONNECT_PACKAGE")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(playStoreIntent)
            showToast(R.string.text_opening_play_store)
            
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open Play Store, trying web browser", e)
            
            // Fallback: Open web browser with Play Store URL
            try {
                val webIntent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://play.google.com/store/apps/details?id=$HEALTH_CONNECT_PACKAGE")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(webIntent)
                showToast(R.string.text_opening_browser_for_health_connect)
            } catch (webException: Exception) {
                Log.e(TAG, "Failed to open browser", webException)
                showToast(R.string.text_unable_to_install_health_connect)
            }
        }
        
        finish()
    }

    private fun isHealthConnectAvailable(): Boolean {
        return try {
            packageManager.getPackageInfo(HEALTH_CONNECT_PACKAGE, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun showToast(messageResId: Int) {
        ViewUtils.showToast(this, getString(messageResId))
    }
}
