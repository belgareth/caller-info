package com.rakibulcodes.callerinfo

data class NumberFormatPreviewResult(
    val formattedNumber: String?
) {
    val isValid: Boolean
        get() = !formattedNumber.isNullOrEmpty()
}

fun previewNumberFormatting(
    input: String?,
    config: NumberNormalizationConfig
): NumberFormatPreviewResult {
    val formatted = normalizePhoneNumber(input, config).takeIf(String::isNotEmpty)
    return NumberFormatPreviewResult(formatted)
}
