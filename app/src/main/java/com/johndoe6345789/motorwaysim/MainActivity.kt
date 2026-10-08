package com.johndoe6345789.motorwaysim

import android.app.Activity
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowInsetsController
import com.johndoe6345789.motorwaysim.ui.MotorwayView

class MainActivity : Activity() {
    private lateinit var motorway: MotorwayView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        motorway = MotorwayView(this)
        setContentView(motorway)
        motorway.requestFocus()
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        motorway.start()
    }

    override fun onPause() {
        motorway.stop()
        super.onPause()
    }

    private fun hideSystemBars() {
        window.insetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
