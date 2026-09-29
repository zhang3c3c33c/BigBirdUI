package io.bbui.device

internal object ActionRequirements {
    fun actionId(value: Any?): String {
        require(value is String && Regex("[A-Za-z0-9_-]{1,100}").matches(value)) { "动作编号缺失或格式无效" }
        return value
    }
    fun observation(requested: Any?, expected: String, ageMillis: Long, actualGeneration: Long, expectedGeneration: Long) {
        require(requested is String && requested.isNotBlank() && requested == expected) { "截图编号缺失或已过期" }
        check(ageMillis in 0..180000) { "观察已过期，请先查看" }
        check(actualGeneration == expectedGeneration) { "屏幕已旋转或重建，请先查看" }
    }
}
