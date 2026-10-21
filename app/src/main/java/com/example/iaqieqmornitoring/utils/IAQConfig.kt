package com.example.iaqieqmornitoring.utils

object IAQConfig {

    // Breakpoint tables. Values come from ThresholdStore so they can be edited in Settings.
    val tempRange: List<Float> get() = ThresholdStore.get("TEMP")
    val humidityRange: List<Float> get() = ThresholdStore.get("HUMIDITY")
    val co2Range: List<Float> get() = ThresholdStore.get("CO2")
    val pm25Range: List<Float> get() = ThresholdStore.get("PM2.5")
    val pm10Range: List<Float> get() = ThresholdStore.get("PM10")
    val tvocRange: List<Float> get() = ThresholdStore.get("TVOC")
    val hchoRange: List<Float> get() = ThresholdStore.get("HCHO")
    val lightRange: List<Float> get() = ThresholdStore.get("LIGHT")
    val soundRange: List<Float> get() = ThresholdStore.get("SOUND")

    fun getRange(title: String): List<Float> = ThresholdStore.get(title)

    fun getMinMaxRange(title: String): ClosedFloatingPointRange<Float> {
        val range = getRange(title)
        return range.first()..range.last()
    }
}

fun getIAQLevel(value: Float, range: List<Float>): Int {
    return when {
        value <= range[0] -> 0   // Good
        value <= range[1] -> 1   // Moderate
        value <= range[2] -> 2   // Polluted
        value <= range[3] -> 3   // Very Polluted
        else -> 4                // Severe
    }
}
