package org.autojs.autojs

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import org.autojs.autojs.ui.BaseActivity
import org.autojs.autojs6.R

/**
 * Activity to show Health Connect permissions rationale.
 * This activity is triggered when users click the privacy policy link in Health Connect.
 * 
 * Created for Health Connect SDK integration.
 * Modified by AutoJs6 team for permissions explanation.
 */
class PermissionsRationaleActivity : BaseActivity() {

    private lateinit var toolbar: Toolbar
    private lateinit var btnUnderstand: MaterialButton
    private lateinit var btnOpenSettings: MaterialButton

    companion object {
        private const val TAG = "PermissionsRationale"
        private const val HEALTH_CONNECT_PACKAGE_NAME = "com.google.android.apps.healthdata"
        
        // Health Connect settings activity components
        private const val HEALTH_CONNECT_SETTINGS_ACTION = "androidx.health.ACTION_MANAGE_HEALTH_PERMISSIONS"
        private const val HEALTH_CONNECT_COMPONENT = "com.google.android.apps.healthdata/.permission.ui.RequestPermissionActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permissions_rationale)
        
        Log.d(TAG, "PermissionsRationaleActivity created")
        
        initViews()
        setupToolbar()
        setupClickListeners()
        
        // Check if Health Connect is available
        if (!isHealthConnectAvailable()) {
            showHealthConnectNotAvailable()
        }
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        btnUnderstand = findViewById(R.id.btn_understand)
        btnOpenSettings = findViewById(R.id.btn_open_settings)
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
            title = getString(R.string.health_connect_permissions_rationale_title)
        }
    }

    private fun setupClickListeners() {
        btnUnderstand.setOnClickListener {
            Log.d(TAG, "User clicked understand button")
            finish()
        }

        btnOpenSettings.setOnClickListener {
            Log.d(TAG, "User clicked open settings button")
            openHealthConnectSettings()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun isHealthConnectAvailable(): Boolean {
        return try {
            packageManager.getPackageInfo(HEALTH_CONNECT_PACKAGE_NAME, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Health Connect is not installed on this device")
            false
        }
    }

    private fun showHealthConnectNotAvailable() {
        val message = getString(R.string.health_connect_permissions_rationale_not_available)
        Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_LONG)
            .show()
        
        // Disable the open settings button
        btnOpenSettings.isEnabled = false
        btnOpenSettings.alpha = 0.6f
    }

    private fun openHealthConnectSettings() {
        if (!isHealthConnectAvailable()) {
            showHealthConnectNotAvailable()
            return
        }

        try {
            // Try to open Health Connect permissions directly
            val intent = Intent().apply {
                action = HEALTH_CONNECT_SETTINGS_ACTION
                `package` = HEALTH_CONNECT_PACKAGE_NAME
            }
            
            // Check if the intent can be resolved
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
                Log.d(TAG, "Opened Health Connect settings with direct action")
            } else {
                // Fallback: try to open Health Connect app main activity
                openHealthConnectApp()
            }
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Failed to open Health Connect settings with direct action", e)
            openHealthConnectApp()
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error opening Health Connect settings", e)
            showSettingsError()
        }
    }

    private fun openHealthConnectApp() {
        try {
            // Try to open the Health Connect app's main activity
            val intent = packageManager.getLaunchIntentForPackage(HEALTH_CONNECT_PACKAGE_NAME)
            if (intent != null) {
                startActivity(intent)
                Log.d(TAG, "Opened Health Connect app main activity")
            } else {
                // Fallback: try to open in Play Store
                openInPlayStore()
            }
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Failed to open Health Connect app", e)
            openInPlayStore()
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error opening Health Connect app", e)
            showSettingsError()
        }
    }

    private fun openInPlayStore() {
        try {
            val playStoreIntent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("market://details?id=$HEALTH_CONNECT_PACKAGE_NAME")
            }
            
            if (playStoreIntent.resolveActivity(packageManager) != null) {
                startActivity(playStoreIntent)
                Log.d(TAG, "Opened Health Connect in Play Store")
            } else {
                // Fallback to web browser
                val webIntent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://play.google.com/store/apps/details?id=$HEALTH_CONNECT_PACKAGE_NAME")
                }
                startActivity(webIntent)
                Log.d(TAG, "Opened Health Connect in web browser")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open Health Connect in Play Store", e)
            showSettingsError()
        }
    }

    private fun showSettingsError() {
        val message = "Unable to open Health Connect settings. Please open Health Connect manually from your app drawer."
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
