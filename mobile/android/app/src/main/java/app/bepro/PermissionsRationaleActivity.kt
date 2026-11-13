package app.bepro

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView

/** Health Connect requires an in-app explanation of how its data is used before it shows the permission dialog. */
class PermissionsRationaleActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val padding = (24 * resources.displayMetrics.density).toInt()
    val text = TextView(this).apply {
      setPadding(padding, padding, padding, padding)
      textSize = 16f
      text = getString(R.string.health_rationale)
    }
    setContentView(ScrollView(this).apply { addView(text) })
  }
}
