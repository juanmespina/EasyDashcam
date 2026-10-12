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
 *  exists in both Record and Capture). */
val SETTING_DESCRIPTIONS = mapOf(
    0x0000 to "Video quality. Higher resolution = sharper plates and signs, bigger files.",
    0x0003 to "Length of each loop file. When the card fills up, the oldest clip is overwritten.",
    0x0004 to "Wide Dynamic Range — balances bright sky against dark shadows, e.g. exiting a tunnel.",
    0x0005 to "Include microphone audio in recordings.",
    0x0100 to "Still photo size in megapixels.",
    0x0101 to "Manual brightness. Raise for dark scenes, lower if the sky is blown out.",
    0x0108 to "Color temperature for the lighting conditions; Auto is fine for most drives.",
    0x0200 to "Mains frequency for your region (50 Hz Europe, 60 Hz Americas). The wrong value makes streetlights and dashboards flicker in video.",
    0x0201 to "How long the camera's LCD stays on before turning itself off.",
    0x0205 to "Order of the date stamp burned into recordings.",
    0x0209 to "Camera firmware version.",
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
