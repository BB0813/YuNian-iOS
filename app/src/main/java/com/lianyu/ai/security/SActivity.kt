package com.lianyu.ai.security

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.lianyu.ai.MainActivity

class SActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OnePieceShellGate.verifyBeforePayload(this)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
        overridePendingTransition(0, 0)
    }
}
