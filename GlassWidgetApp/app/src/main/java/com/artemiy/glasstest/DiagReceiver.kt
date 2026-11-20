package com.artemiy.glasstest

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** v0.8: принимает отчёты GlassRootView из процесса хоста и пишет их в logcat (тег GlassDiag). */
class DiagReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION = "com.artemiy.glasstest.DIAG"
    }

    override fun onReceive(c: Context, i: Intent) {
        Log.i("GlassDiag", i.getStringExtra("msg") ?: "(empty)")
    }
}
