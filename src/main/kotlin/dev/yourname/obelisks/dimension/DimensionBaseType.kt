package dev.yourname.obelisks.dimension

enum class DimensionBaseType {
    NETHER,
    END;

    companion object {
        fun random(): DimensionBaseType = entries.random()
    }
}
