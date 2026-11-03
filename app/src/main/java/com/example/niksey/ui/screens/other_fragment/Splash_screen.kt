package com.example.niksey.ui.screens.other_fragment

import android.content.Intent
import android.content.pm.ActivityInfo
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.example.niksey.MainActivity
import com.example.niksey.R

@Suppress("DEPRECATION")
class splash_screen : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash_screen)
        /*Отключение автоповорота*/
        setRequestedOrientation (ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
        /*Отключение автоповорота*/
        Handler(Looper.getMainLooper()).postDelayed({
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
            finish()
        }, 1000)
    }
}