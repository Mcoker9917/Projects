package dev.duolauncher

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URLEncoder

/** Shortcuts into the Claude app (or claude.ai in the browser if the app isn't installed). */
object Claude {
    private const val PKG = "com.anthropic.claude"

    private fun installed(ctx: Context) = ctx.packageManager.getLaunchIntentForPackage(PKG) != null

    fun open(ctx: Context) {
        val intent = ctx.packageManager.getLaunchIntentForPackage(PKG)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://claude.ai/new"))
        start(ctx, intent)
    }

    /** Hand [text] to Claude as a new message. */
    fun ask(ctx: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).setPackage(PKG)
        if (installed(ctx) && send.resolveActivity(ctx.packageManager) != null) {
            start(ctx, send)
        } else {
            start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://claude.ai/new?q=" + URLEncoder.encode(text, "UTF-8"))))
        }
    }

    /** Claude Code sessions (opens in the app when it handles claude.ai links). */
    fun code(ctx: Context) = start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://claude.ai/code")))

    private fun start(ctx: Context, i: Intent) {
        try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            // Nothing can open it.
        }
    }
}
