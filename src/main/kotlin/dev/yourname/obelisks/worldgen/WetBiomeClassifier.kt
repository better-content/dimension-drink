package dev.yourname.obelisks.worldgen

object WetBiomeClassifier {
    fun matchesId(id: String): Boolean {
        val path = id.substringAfter(':', id).lowercase()
        return path.contains("swamp") ||
            path.contains("mangrove") ||
            path.contains("marsh") ||
            path.contains("bayou") ||
            path.contains("bog") ||
            path.contains("wetland") ||
            path.contains("rainforest") ||
            path.contains("jungle") ||
            path.contains("river") ||
            path.contains("ocean") ||
            path.contains("lush") ||
            path.contains("tropical") ||
            path.contains("fen") ||
            path.contains("mire") ||
            path.contains("moor") ||
            path.contains("lagoon") ||
            path.contains("reef") ||
            path.contains("delta") ||
            path.contains("flood") ||
            path.contains("cypress") ||
            path.contains("willow") ||
            path.contains("orchid") ||
            path.contains("mushroom")
    }
}
