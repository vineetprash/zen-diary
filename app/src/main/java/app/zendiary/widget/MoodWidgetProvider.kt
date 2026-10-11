package app.zendiary.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.Toast
import app.zendiary.MainActivity
import app.zendiary.R
import app.zendiary.ZenApp
import app.zendiary.ui.theme.Mood
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Home-screen widget: five face buttons = one-tap mood log for today. */
class MoodWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { mgr.updateAppWidget(it, build(ctx)) }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action != ACTION_LOG) return
        val mood = intent.getIntExtra("mood", 0)
        if (mood !in 1..5) return
        val pending = goAsync()
        val c = (ctx.applicationContext as ZenApp).container
        c.scope.launch(Dispatchers.IO) {
            try {
                c.diary.quickMood(mood)
                withContext(Dispatchers.Main) { Toast.makeText(ctx, "Logged: ${Mood.of(mood).label}", Toast.LENGTH_SHORT).show() }
            } finally { pending.finish() }
        }
    }

    private fun build(ctx: Context): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.widget_mood)
        val ids = intArrayOf(R.id.mood_1, R.id.mood_2, R.id.mood_3, R.id.mood_4, R.id.mood_5)
        ids.forEachIndexed { i, id ->
            val intent = Intent(ctx, MoodWidgetProvider::class.java).setAction(ACTION_LOG).putExtra("mood", i + 1)
            rv.setOnClickPendingIntent(id, PendingIntent.getBroadcast(ctx, i + 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        rv.setOnClickPendingIntent(R.id.widget_title, open)
        return rv
    }

    companion object { const val ACTION_LOG = "app.zendiary.LOG_MOOD" }
}
