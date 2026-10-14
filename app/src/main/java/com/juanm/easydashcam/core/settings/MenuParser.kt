package com.juanm.easydashcam.core.settings

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/** One selectable value of an enum setting. */
data class SettingOption(val name: String, val id: Int)

/** A settings entry from camera_menu.xml. type: 0x00 enum, 0x01 action, 0x03 read-only. */
data class CameraSetting(
    val name: String,
    val id: Int,
    val type: Int,
    val options: List<SettingOption>
) {
    val isEnum get() = type == 0x00 && options.isNotEmpty()
    /** Two-option On/Off pairs render as a switch instead of a dropdown. */
    val isToggle get() = isEnum && options.size == 2 &&
        options[0].name.equals("Off", true) && options[1].name.equals("On", true)
}

data class SettingCategory(val name: String, val settings: List<CameraSetting>)

/** Plain-language explanations per param id (names repeat — "Resolution"
 *  exists in both Record and Capture). String resources, resolved by the UI. */
val SETTING_DESCRIPTIONS = mapOf(
    0x0000 to com.juanm.easydashcam.R.string.setdesc_0000,
    0x0003 to com.juanm.easydashcam.R.string.setdesc_0003,
    0x0004 to com.juanm.easydashcam.R.string.setdesc_0004,
    0x0005 to com.juanm.easydashcam.R.string.setdesc_0005,
    0x0100 to com.juanm.easydashcam.R.string.setdesc_0100,
    0x0101 to com.juanm.easydashcam.R.string.setdesc_0101,
    0x0108 to com.juanm.easydashcam.R.string.setdesc_0108,
    0x0200 to com.juanm.easydashcam.R.string.setdesc_0200,
    0x0201 to com.juanm.easydashcam.R.string.setdesc_0201,
    0x0205 to com.juanm.easydashcam.R.string.setdesc_0205,
    0x0209 to com.juanm.easydashcam.R.string.setdesc_0209,
)

/** Parses the camera's settings menu document into UI-ready categories. */
object MenuParser {

    fun parse(xml: String): List<SettingCategory> {
        val parser = Xml.newPullParser().apply {
            setInput(StringReader(xml))
        }
        val categories = mutableListOf<SettingCategory>()
        var category: String? = null
        val settings = mutableListOf<CameraSetting>()
        var name: String? = null
        var id: Int? = null
        var type = 0
        var inSetting = false
        var inValue = false
        val options = mutableListOf<SettingOption>()
        var optName: String? = null
        var optId: Int? = null
        var text: String? = null

        fun flushCategory() {
            category?.let { categories += SettingCategory(it, settings.toList()) }
            settings.clear()
        }
        fun flushSetting() {
            if (name != null && id != null) {
                settings += CameraSetting(name!!, id!!, type, options.toList())
            }
            name = null; id = null; type = 0; options.clear()
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    text = null
                    when (parser.name) {
                        "Category" -> flushCategory().also { category = null }
                        "Setting" -> { inSetting = true; flushSetting() }
                        "Value" -> inValue = true
                    }
                }
                XmlPullParser.TEXT -> text = parser.text
                XmlPullParser.END_TAG -> when (parser.name) {
                    "Category" -> flushCategory().also { category = null }
                    "Setting" -> { flushSetting(); inSetting = false }
                    "Value" -> {
                        if (optName != null && optId != null) options += SettingOption(optName!!, optId!!)
                        optName = null; optId = null; inValue = false
                    }
                    "Name" -> when {
                        inValue -> optName = text
                        inSetting -> name = text
                        else -> category = text
                    }
                    "ID" -> {
                        val v = text?.removePrefix("0x")?.toIntOrNull(16)
                        if (inValue) optId = v else id = v
                    }
                    "Type" -> type = text?.removePrefix("0x")?.toIntOrNull(16) ?: 0
                }
            }
            event = parser.next()
        }
        return categories
    }
}
