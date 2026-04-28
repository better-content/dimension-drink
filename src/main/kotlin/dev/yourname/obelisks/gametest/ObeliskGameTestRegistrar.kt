package dev.yourname.obelisks.gametest

import net.minecraftforge.event.RegisterGameTestsEvent
import net.minecraftforge.eventbus.api.SubscribeEvent

object ObeliskGameTestRegistrar {
    private val selection: String
        get() = System.getProperty("obelisks.gametest.selection", "all").lowercase()

    @SubscribeEvent
    fun onRegisterGameTests(event: RegisterGameTestsEvent) {
        when (selection) {
            "run" -> event.register(ObeliskRunLifecycleGameTests::class.java)
            "activation" -> event.register(ObeliskActivationGameTests::class.java)
            "rewards" -> event.register(ObeliskRewardsGameTests::class.java)
            "void" -> event.register(ObeliskVoidGameTests::class.java)
            "data" -> event.register(ObeliskDataGameTests::class.java)
            "template" -> event.register(ObeliskTemplateMappingGameTests::class.java)
            "multiplayer" -> event.register(ObeliskMultiplayerGameTests::class.java)
            "commands" -> event.register(ObeliskCommandGameTests::class.java)
            "runtime" -> event.register(ObeliskRuntimeGameTests::class.java)
            else -> {
                event.register(ObeliskRunLifecycleGameTests::class.java)
                event.register(ObeliskActivationGameTests::class.java)
                event.register(ObeliskRewardsGameTests::class.java)
                event.register(ObeliskVoidGameTests::class.java)
                event.register(ObeliskDataGameTests::class.java)
                event.register(ObeliskTemplateMappingGameTests::class.java)
                event.register(ObeliskMultiplayerGameTests::class.java)
                event.register(ObeliskCommandGameTests::class.java)
                event.register(ObeliskRuntimeGameTests::class.java)
            }
        }
    }
}
