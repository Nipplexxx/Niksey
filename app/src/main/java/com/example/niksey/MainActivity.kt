@file:Suppress("DEPRECATION")
package com.example.niksey

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.example.niksey.database.initFirebase
import com.example.niksey.database.initUser
import com.example.niksey.databinding.ActivityMainBinding
import com.example.niksey.models.UserDataManager
import com.example.niksey.ui.objects.AppDrawer
import com.example.niksey.ui.screens.main_list.MainListFragment
import com.example.niksey.ui.screens.register.EnteredFragment
import com.example.niksey.utillits.*
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

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* ничего не делаем */ }

    @RequiresApi(Build.VERSION_CODES.S)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(mBinding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        APP_ACTIVITY = this

        requestAllPermissions()

        ChatEncryptionManager.init(this)
        PostQuantumKeyManager.generateECDHKeyPair()
        initFirebase()

        handleEmailLinkIfPresent()

        if (AUTH.currentUser != null) {
            checkBiometricAndInit()
        } else {
            replaceFragment(EnteredFragment(), false)
        }
    }

    private fun requestAllPermissions() {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val alreadyRequested = prefs.getBoolean("permissions_requested", false)

        if (alreadyRequested) return   // Не запрашиваем повторно

        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.RECORD_AUDIO
            )
        } else {
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.RECORD_AUDIO
            )
        }

        requestPermissionsLauncher.launch(permissions)

        // Сохраняем флаг, что уже запрашивали
        prefs.edit { putBoolean("permissions_requested", true) }
    }

    private fun handleEmailLinkIfPresent() {
        try {
            val emailLink = intent?.data?.toString()
            if (emailLink != null && AUTH.isSignInWithEmailLink(emailLink)) {
                val prefs = getSharedPreferences("auth", MODE_PRIVATE)
                val pendingEmail = prefs.getString("pending_email", null)

                if (pendingEmail != null) {
                    AUTH.signInWithEmailLink(pendingEmail, emailLink)
                        .addOnCompleteListener { task ->
                            if (task.isSuccessful) {
                                prefs.edit { remove("pending_email") }
                                initUser { initApp() }
                            } else {
                                Toast.makeText(
                                    this,
                                    "Не удалось войти по ссылке: ${task.exception?.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                                replaceFragment(EnteredFragment(), false)
                            }
                        }
                } else {
                    replaceFragment(EnteredFragment(), false)
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка обработки ссылки: ${e.message}", Toast.LENGTH_LONG).show()
            replaceFragment(EnteredFragment(), false)
        }
    }

    // ==================== БИОМЕТРИЯ ====================
    private fun checkBiometricAndInit() {
        val biometricManager = BiometricManager.from(this)

        when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> {
                setupBiometricPrompt()
                showBiometricPrompt()
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

    private fun showBiometricPrompt() {
        isAppLocked = true
        biometricPrompt.authenticate(promptInfo)
    }

    private fun initApp() {
        initFields()
        initFunc()
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)

        lifecycleScope.launch(Dispatchers.IO) {
            initContacts()
        }
    }

    private fun initFunc() {
        setSupportActionBar(mToolbar)
        mAppDrawer.create()

        // Небольшая задержка для стабильности
        mBinding.root.postDelayed({
            replaceFragment(MainListFragment(), false)
        }, 180)
    }

    private fun initFields() {
        mToolbar = mBinding.mainToolbar
        mAppDrawer = AppDrawer()
    }

    override fun onStart() {
        super.onStart()
        AppStates.updateState(AppStates.ONLINE)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            AppStates.updateState(AppStates.OFFLINE)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            ChatEncryptionManager.clear()
            UserDataManager.clearUser(this)
        }
    }
}