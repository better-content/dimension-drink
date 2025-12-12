package dev.yourname.obelisks.dimension

enum class DimensionBaseType {
    NETHER,
    END,
    OVERWORLD;

    companion object {
        fun random(): DimensionBaseType = entries.random()
    }
}
