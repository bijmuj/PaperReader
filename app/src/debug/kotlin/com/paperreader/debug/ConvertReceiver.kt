package com.paperreader.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.paperreader.convert.convertPdf
import java.io.File

/**
 * adb shell am broadcast -n com.paperreader/.debug.ConvertReceiver --es name attention
 * converts files/debug/attention.pdf to files/debug/attention.epub (log tag PdfDebug).
 */
class ConvertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.getStringExtra("name")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) } ?: return
        val dir = File(context.filesDir, "debug")
        val pending = goAsync()
        Thread {
            val start = System.currentTimeMillis()
            try {
                convertPdf(File(dir, "$name.pdf"), File(dir, "$name.epub")) { Log.d("PdfDebug", "$name: $it") }
                Log.i("PdfDebug", "$name: done in ${System.currentTimeMillis() - start} ms")
            } catch (e: Throwable) {
                Log.e("PdfDebug", "$name: failed", e)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
