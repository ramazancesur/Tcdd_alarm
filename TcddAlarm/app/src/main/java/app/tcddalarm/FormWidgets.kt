package app.tcddalarm

import android.app.Activity
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.Spinner
import android.widget.TextView

/** Programatik form parçaları. Activity içinde tekrarlanan View kurulumunu toplar. */
internal class FormWidgets(private val activity: Activity) {
    fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density + 0.5f).toInt()

    fun header(text: String): TextView = TextView(activity).apply {
        this.text = text
        textSize = 17f
        setTypeface(null, Typeface.BOLD)
        setPadding(0, dp(20), 0, dp(4))
    }

    fun hint(text: String): TextView = TextView(activity).apply {
        this.text = text
        textSize = 12f
    }

    fun button(text: String, onClick: () -> Unit): Button = Button(activity).apply {
        this.text = text
        transformationMethod = null
        setOnClickListener { onClick() }
    }

    fun check(text: String): CheckBox = CheckBox(activity).apply { this.text = text }

    fun radio(text: String): RadioButton = RadioButton(activity).apply {
        id = View.generateViewId()
        this.text = text
    }

    fun spinner(items: List<String>): Spinner = Spinner(activity).apply {
        adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, items)
    }

    fun row(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
    }

    fun half(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        0,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        1f,
    )
}
