package own.moderpach.extinguish.service

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class QuickScreenOffActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val screen = intent.getIntExtra(QuickScreenOffService.EXTRA_SCREEN, -1)
        val timer = intent.getIntExtra(QuickScreenOffService.EXTRA_TIMER, -1)
        if (screen in 0..1) {
            startForegroundService(
                Intent(this, QuickScreenOffService::class.java).apply {
                    putExtra(QuickScreenOffService.EXTRA_SCREEN, screen)
                    if (timer > 0) putExtra(QuickScreenOffService.EXTRA_TIMER, timer)
                }
            )
        }

        finish()
        overridePendingTransition(0, 0)
    }
}
