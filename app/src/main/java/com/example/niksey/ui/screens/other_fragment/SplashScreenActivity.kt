package com.example.niksey.ui.screens.other_fragment

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.example.niksey.MainActivity
import com.example.niksey.R

/** Заставка при запуске: показывает логотип и через секунду передаёт управление [MainActivity]. */
class SplashScreenActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash_screen)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }, SPLASH_DELAY_MS)
    }

    private companion object {
        const val SPLASH_DELAY_MS = 1000L
    }
}
