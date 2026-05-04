package com.example.niksey

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.niksey.database.AUTH
import com.example.niksey.database.initFirebase
import com.example.niksey.database.initUser
import com.example.niksey.databinding.ActivityMainBinding
import com.example.niksey.ui.objects.AppDrawer
import com.example.niksey.ui.screens.main_list.MainListFragment
import com.example.niksey.ui.screens.register.EnteredFragment
import com.example.niksey.utillits.APP_ACTIVITY
import com.example.niksey.utillits.AppStates
import com.example.niksey.utillits.initContacts
import com.example.niksey.utillits.replaceFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.Executor

@SuppressLint("SourceLockedOrientationActivity")
class MainActivity : AppCompatActivity() {

    private lateinit var mBinding: ActivityMainBinding
    lateinit var mAppDrawer: AppDrawer
    lateinit var mToolbar: Toolbar

    private lateinit var biometricPrompt: BiometricPrompt
    private lateinit var promptInfo: BiometricPrompt.PromptInfo
    private var isAppLocked = false
    private var lastPauseTime = 0L
    private var isBiometricEnabled = true

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            initAppAfterPermissions()
        } else {
            Toast.makeText(this, getString(R.string.permission_denied), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(mBinding.root)

        APP_ACTIVITY = this
        initFirebase()
        checkBiometricAndInit()
    }

    private fun checkBiometricAndInit() {
        val biometricManager = BiometricManager.from(this)
        when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> {
                setupBiometricPrompt()
                if (isBiometricEnabled) {
                    showBiometricPrompt { initUser { initApp() } }
                } else {
                    initUser { initApp() }
                }
            }
            else -> {
                initUser { initApp() }
            }
        }
    }

    private fun setupBiometricPrompt() {
        val executor: Executor = ContextCompat.getMainExecutor(this)
        biometricPrompt = BiometricPrompt(
            this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    isAppLocked = false
                    if (!isFinishing) {
                        initUser { initApp() }
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    Toast.makeText(this@MainActivity, getString(R.string.biometric_error), Toast.LENGTH_SHORT).show()
                    finish()
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Toast.makeText(this@MainActivity, getString(R.string.biometric_failed), Toast.LENGTH_SHORT).show()
                }
            })

        promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.biometric_title))
            .setSubtitle(getString(R.string.biometric_subtitle))
            .setNegativeButtonText(getString(R.string.cancel))
            .build()
    }

    private fun showBiometricPrompt(onSuccess: () -> Unit) {
        isAppLocked = true
        biometricPrompt.authenticate(promptInfo)
    }

    private fun initApp() {
        lifecycleScope.launch(Dispatchers.IO) {
            initContacts()
        }
        initFields()
        initFunc()
        AppStates.updateState(AppStates.ONLINE)
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        setSmartStatus()
    }

    private fun setSmartStatus() {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        if (hour in 9..17) {
            mToolbar.setNavigationIcon(android.R.drawable.ic_menu_agenda)
            mToolbar.setNavigationContentDescription(getString(R.string.smart_status_meeting))
        }
    }

    private fun initFunc() {
        setSupportActionBar(mToolbar)
        if (AUTH.currentUser != null) {
            mAppDrawer.create()
            replaceFragment(MainListFragment(), false)
        } else {
            replaceFragment(EnteredFragment(), false)
        }
    }

    private fun initFields() {
        mToolbar = mBinding.mainToolbar
        mAppDrawer = AppDrawer()
    }

    fun showAIQuickReplies(onReplySelected: (String) -> Unit) {
        val bottomSheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_ai_replies, null)
        bottomSheet.setContentView(view)

        val reply1 = view.findViewById<TextView>(R.id.reply_1)
        val reply2 = view.findViewById<TextView>(R.id.reply_2)
        val reply3 = view.findViewById<TextView>(R.id.reply_3)
        val reply4 = view.findViewById<TextView>(R.id.reply_4)

        val replies = listOf(
            getString(R.string.ai_reply_thanks),
            getString(R.string.ai_reply_ok),
            getString(R.string.ai_reply_later),
            getString(R.string.ai_reply_call_me)
        )

        reply1.setOnClickListener {
            onReplySelected(replies[0])
            bottomSheet.dismiss()
        }

        reply2.setOnClickListener {
            onReplySelected(replies[1])
            bottomSheet.dismiss()
        }

        reply3.setOnClickListener {
            onReplySelected(replies[2])
            bottomSheet.dismiss()
        }

        reply4.setOnClickListener {
            onReplySelected(replies[3])
            bottomSheet.dismiss()
        }

        bottomSheet.show()
    }

    private fun initAppAfterPermissions() {
        lifecycleScope.launch(Dispatchers.IO) {
            initContacts()
        }
        initUser { initApp() }
    }

    override fun onStop() {
        super.onStop()
        AppStates.updateState(AppStates.OFFLINE)
        lastPauseTime = System.currentTimeMillis()
    }

    override fun onStart() {
        super.onStart()
        AppStates.updateState(AppStates.ONLINE)

        if (isBiometricEnabled && isAppLocked && System.currentTimeMillis() - lastPauseTime > 60000) {
            showBiometricPrompt {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}