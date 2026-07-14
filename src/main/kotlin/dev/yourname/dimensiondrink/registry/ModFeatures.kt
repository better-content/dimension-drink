package dev.yourname.dimensiondrink.registry

import dev.yourname.dimensiondrink.MOD_ID
import dev.yourname.dimensiondrink.worldgen.ObeliskFeature
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration
import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.ForgeRegistries
import net.minecraftforge.registries.RegistryObject

object ModFeatures {
    val REGISTRY: DeferredRegister<Feature<*>> =
        DeferredRegister.create(ForgeRegistries.FEATURES, MOD_ID)

    val OBELISK: RegistryObject<Feature<NoneFeatureConfiguration>> = REGISTRY.register("dimensional_font") {
        ObeliskFeature(NoneFeatureConfiguration.CODEC)
    }
}
