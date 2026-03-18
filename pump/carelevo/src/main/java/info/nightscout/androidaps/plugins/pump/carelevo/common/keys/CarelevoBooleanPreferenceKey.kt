package info.nightscout.androidaps.plugins.pump.carelevo.common.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey

enum class CarelevoBooleanPreferenceKey(
    override val key: String,
    override val defaultValue: Boolean,
    override val calculatedDefaultValue: Boolean = false,
    override val engineeringModeOnly: Boolean = false,
    override val defaultedBySM: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
    override val exportable: Boolean = true
) : BooleanPreferenceKey {

    CARELEVO_PATCH_EXPIRATION_REMINDER_ENABLED("CARELEVO_PATCH_EXPIRATION_REMINDER_ENABLED", true),
    CARELEVO_LOW_RESERVOIR_REMINDER_ENABLED("CARELEVO_LOW_RESERVOIR_REMINDER_ENABLED", true),

    CARELEVO_BUZZER_REMINDER("CARELEVO_BUZZER_REMINDER", false),
    CARELEVO_CAGE_DEFAULT_APPLIED("carelevo_cage_default_applied", false)
}
