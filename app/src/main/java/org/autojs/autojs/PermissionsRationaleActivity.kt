package org.autojs.autojs

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import com.google.android.material.button.MaterialButton
import org.autojs.autojs.ui.BaseActivity
import org.autojs.autojs6.R
import org.autojs.autojs6.databinding.ActivityPermissionsRationaleBinding

/**
 * Activity to show Health Connect permissions rationale.
 * This activity handles the Health Connect permissions explanation and 
 * allows users to navigate to Health Connect settings.
 * 
 * Created for Health Connect SDK integration.
 */
class PermissionsRationaleActivity : BaseActivity() {

    private lateinit var binding: ActivityPermissionsRationaleBinding

    companion object {
        private const val TAG = "PermissionsRationale"
        private const val HEALTH_CONNECT_PACKAGE = "com.google.android.apps.healthdata"
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
        
        setupUI()
    }

    private fun setupUI() {
        binding.apply {
            // Set up button click listeners
            buttonGoToSettings.setOnClickListener {
                openHealthConnectSettings()
            }
            
            buttonNotNow.setOnClickListener {
                finish()
            }
        }
    }

    private fun openHealthConnectSettings() {
        try {
            // Try to open Health Connect app settings
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", HEALTH_CONNECT_PACKAGE, null)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open Health Connect settings", e)
            
            // Fallback: Open general app settings or system settings
            try {
                val fallbackIntent = Intent(Settings.ACTION_SETTINGS)
                startActivity(fallbackIntent)
            } catch (fallbackException: Exception) {
                Log.e(TAG, "Failed to open any settings", fallbackException)
            }
        }
        
        // Close this activity after trying to open settings
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
}
