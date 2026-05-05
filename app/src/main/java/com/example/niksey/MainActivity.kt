package com.example.niksey

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
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
import com.example.niksey.utillits.ChatEncryptionManager
import com.example.niksey.utillits.PostQuantumKeyManager
import com.example.niksey.utillits.UserDataManager
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(mBinding.root)

        // Фикс для статус-бара и клавиатуры (Android 12+)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        APP_ACTIVITY = this

        // Инициализируем новую систему шифрования (пост-квантовое)
        ChatEncryptionManager.init(this)
        PostQuantumKeyManager.generateECDHKeyPair() // Генерируем ECDH ключ при первом запуске

        initFirebase()

        if (AUTH.currentUser != null) {
            checkBiometricAndInit()
        } else {
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
    // ====================================================

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

        // Небольшая задержка для стабильности на эмуляторе
        mBinding.root.postDelayed({
            replaceFragment(MainListFragment(), false)
        }, 180)
    }

    private fun initFields() {
        mToolbar = mBinding.mainToolbar
        mAppDrawer = AppDrawer()
    }

    // ==================== AI Quick Reply ====================
    fun showAIQuickReplies(onReplySelected: (String) -> Unit) {
        val bottomSheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_ai_replies, null)
        bottomSheet.setContentView(view)

        val reply1 = view.findViewById<android.widget.TextView>(R.id.reply_1)
        val reply2 = view.findViewById<android.widget.TextView>(R.id.reply_2)
        val reply3 = view.findViewById<android.widget.TextView>(R.id.reply_3)
        val reply4 = view.findViewById<android.widget.TextView>(R.id.reply_4)

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
    // ====================================================

    override fun onStart() {
        super.onStart()
        // Пользователь вернулся в приложение — ставим ONLINE
        AppStates.updateState(AppStates.ONLINE)
    }

    override fun onStop() {
        super.onStop()
        // Только если не поворот экрана и не переход в другое Activity
        if (!isChangingConfigurations) {
            AppStates.updateState(AppStates.OFFLINE)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Очищаем кэш шифрования и данные пользователя при полном выходе из приложения
        if (isFinishing) {
            ChatEncryptionManager.clear()
            UserDataManager.clearUser(this)
        }
    }
}