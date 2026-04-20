package dev.yourname.instanceddimensions.engine.instance

data class InstanceTemplate(
    val id: String,
    val stem: String,
    val requiredNamespace: String? = null,
    val ephemeral: Boolean = true,
    val description: String = ""
)
