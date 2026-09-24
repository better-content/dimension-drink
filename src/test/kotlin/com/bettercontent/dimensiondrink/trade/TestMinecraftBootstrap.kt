package com.bettercontent.dimensiondrink.trade

import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap

/** Minimal vanilla registry bootstrap for contract tests that instantiate ItemStack/MerchantOffer. */
internal object TestMinecraftBootstrap {
    private var bootstrapped = false

    fun bootstrap() {
        if (bootstrapped) return
        SharedConstants.tryDetectVersion()
        try {
            Bootstrap.bootStrap()
        } catch (error: ExceptionInInitializerError) {
            val knownPlainJvmForgeBoundary = generateSequence<Throwable>(error) { it.cause }
                .filterIsInstance<NoSuchMethodException>()
                .any { it.message?.startsWith("net.minecraftforge.network.NetworkEvent") == true }
            if (!knownPlainJvmForgeBoundary) throw error
        }
        bootstrapped = true
    }
}
