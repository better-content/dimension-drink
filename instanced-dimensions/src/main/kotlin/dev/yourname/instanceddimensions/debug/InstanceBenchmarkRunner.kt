package dev.yourname.instanceddimensions.debug

import com.mojang.logging.LogUtils
import dev.yourname.instanceddimensions.api.InstanceCreateResult
import dev.yourname.instanceddimensions.compat.C2meCompat
import dev.yourname.instanceddimensions.engine.instance.InstanceManager
import dev.yourname.instanceddimensions.engine.instance.InstanceState
import net.minecraft.server.MinecraftServer
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.server.ServerStartedEvent
import net.minecraftforge.event.server.ServerStoppedEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.UUID
import kotlin.io.path.absolute
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

object InstanceBenchmarkRunner {

    private const val BENCHMARK_ENABLED_PROPERTY = "instanceddimensions.benchmark"
    private const val BENCHMARK_COUNTS_PROPERTY = "instanceddimensions.benchmark.counts"
    private const val BENCHMARK_TEMPLATE_PROPERTY = "instanceddimensions.benchmark.template"
    private const val BENCHMARK_STEADY_TICKS_PROPERTY = "instanceddimensions.benchmark.steadyTicks"
    private const val BENCHMARK_OUTPUT_PROPERTY = "instanceddimensions.benchmark.output"

    private val logger = LogUtils.getLogger()
    private val enabled: Boolean = java.lang.Boolean.getBoolean(BENCHMARK_ENABLED_PROPERTY)
    private val templateId: String = System.getProperty(BENCHMARK_TEMPLATE_PROPERTY, "end")
    private val steadyTicks: Int = System.getProperty(BENCHMARK_STEADY_TICKS_PROPERTY, "100").toIntOrNull()?.coerceAtLeast(20) ?: 100
    private val scenarioCounts: List<Int> = parseScenarioCounts(System.getProperty(BENCHMARK_COUNTS_PROPERTY, "1,5,10,20"))
    private val outputPath: Path = Path.of(System.getProperty(BENCHMARK_OUTPUT_PROPERTY, "benchmark-results/instance-benchmark.json"))

    private var activeRun: BenchmarkRun? = null
    private var steadyTickStartNs: Long = 0L

    @SubscribeEvent
    fun onServerStarted(event: ServerStartedEvent) {
        if (!enabled) {
            return
        }

        val server = event.server
        activeRun = BenchmarkRun(
            templateId = templateId,
            steadyTicks = steadyTicks,
            scenarioCounts = scenarioCounts
        )
        logger.info(
            "Instance benchmark enabled: template={}, steadyTicks={}, scenarios={}, output={}, c2meProfile={}",
            templateId,
            steadyTicks,
            scenarioCounts.joinToString(","),
            outputPath.toAbsolutePath(),
            C2meCompat.profileName()
        )
        activeRun?.start(server)
    }

    @SubscribeEvent
    fun onServerTick(event: TickEvent.ServerTickEvent) {
        val run = activeRun ?: return
        if (event.phase == TickEvent.Phase.START) {
            if (run.isMeasuringSteadyState) {
                steadyTickStartNs = System.nanoTime()
            }
            return
        }

        if (steadyTickStartNs != 0L && run.isMeasuringSteadyState) {
            run.recordSteadyTick(System.nanoTime() - steadyTickStartNs)
            steadyTickStartNs = 0L
        }
        run.tick(event.server)
        if (run.isFinished) {
            writeResults(run)
            activeRun = null
        }
    }

    @SubscribeEvent
    fun onServerStopped(@Suppress("UNUSED_PARAMETER") event: ServerStoppedEvent) {
        activeRun = null
        steadyTickStartNs = 0L
    }

    private fun writeResults(run: BenchmarkRun) {
        outputPath.parent?.createDirectories()
        outputPath.writeText(run.toJson())
        logger.info("Instance benchmark complete; wrote {}", outputPath.toAbsolutePath())
        run.results.forEach { result ->
            logger.info(
                "Benchmark scenario {} instances: createMs={}, steadyAvgTickMs={}, steadyMaxTickMs={}, destroyMs={}, readyHeapMiB={}, postDestroyHeapMiB={}",
                result.instanceCount,
                formatDouble(result.createMs),
                formatDouble(result.steadyAvgTickMs),
                formatDouble(result.steadyMaxTickMs),
                formatDouble(result.destroyMs),
                formatDouble(result.readyHeapMiB),
                formatDouble(result.postDestroyHeapMiB)
            )
        }
    }

    private fun parseScenarioCounts(raw: String): List<Int> {
        val parsed = raw.split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it > 0 }
            .distinct()
            .sorted()
        return if (parsed.isEmpty()) listOf(1, 5, 10, 20) else parsed
    }

    private fun formatDouble(value: Double): String = String.format(Locale.ROOT, "%.3f", value)

    private class BenchmarkRun(
        private val templateId: String,
        private val steadyTicks: Int,
        private val scenarioCounts: List<Int>
    ) {
        val results = mutableListOf<ScenarioResult>()
        private var currentScenarioIndex = 0
        private var state = State.BOOT_DELAY
        private var delayTicksRemaining = 40
        private var currentScenario: Scenario? = null
        private var scenarioStartNs = 0L
        private var scenarioStartTick = 0L
        private var steadyTicksRemaining = 0
        private var steadyTickSamples = 0
        private var steadyTickTotalNs = 0L
        private var steadyTickMaxNs = 0L
        var isFinished = false
            private set

        val isMeasuringSteadyState: Boolean
            get() = state == State.STEADY_STATE

        fun start(server: MinecraftServer) {
            scenarioStartTick = server.overworld().gameTime
        }

        fun recordSteadyTick(deltaNs: Long) {
            if (!isMeasuringSteadyState) {
                return
            }
            steadyTickSamples++
            steadyTickTotalNs += deltaNs
            if (deltaNs > steadyTickMaxNs) {
                steadyTickMaxNs = deltaNs
            }
        }

        fun tick(server: MinecraftServer) {
            if (isFinished) {
                return
            }

            when (state) {
                State.BOOT_DELAY -> {
                    delayTicksRemaining--
                    if (delayTicksRemaining <= 0) {
                        beginCreate(server)
                    }
                }

                State.CREATING -> {
                    val scenario = currentScenario ?: return
                    if (scenario.instanceIds.all { instanceReady(server, it) }) {
                        val readyNs = System.nanoTime()
                        scenario.createMs = nanosToMs(readyNs - scenarioStartNs)
                        scenario.ticksToReady = server.overworld().gameTime - scenarioStartTick
                        scenario.readyHeapMiB = heapUsageMiB()
                        logger.info(
                            "Benchmark scenario {} ready: createMs={}, ticksToReady={}, readyHeapMiB={}",
                            scenario.instanceCount,
                            formatDouble(scenario.createMs),
                            scenario.ticksToReady,
                            formatDouble(scenario.readyHeapMiB)
                        )
                        steadyTicksRemaining = steadyTicks
                        steadyTickSamples = 0
                        steadyTickTotalNs = 0L
                        steadyTickMaxNs = 0L
                        state = State.STEADY_STATE
                    }
                }

                State.STEADY_STATE -> {
                    steadyTicksRemaining--
                    if (steadyTicksRemaining <= 0) {
                        val scenario = currentScenario ?: return
                        scenario.steadyAvgTickMs = if (steadyTickSamples == 0) 0.0 else nanosToMs(steadyTickTotalNs.toDouble() / steadyTickSamples.toDouble())
                        scenario.steadyMaxTickMs = nanosToMs(steadyTickMaxNs)
                        logger.info(
                            "Benchmark scenario {} steady state captured: steadyAvgTickMs={}, steadyMaxTickMs={}",
                            scenario.instanceCount,
                            formatDouble(scenario.steadyAvgTickMs),
                            formatDouble(scenario.steadyMaxTickMs)
                        )
                        beginDestroy(server)
                    }
                }

                State.DESTROYING -> {
                    val scenario = currentScenario ?: return
                    if (scenario.instanceIds.all { InstanceManager.getInstance(it) == null }) {
                        scenario.destroyMs = nanosToMs(System.nanoTime() - scenarioStartNs)
                        scenario.ticksToDestroy = server.overworld().gameTime - scenarioStartTick
                        scenario.postDestroyHeapMiB = heapUsageMiB()
                        results += scenario.toResult()
                        logger.info(
                            "Benchmark scenario {} destroyed: destroyMs={}, ticksToDestroy={}, postDestroyHeapMiB={}",
                            scenario.instanceCount,
                            formatDouble(scenario.destroyMs),
                            scenario.ticksToDestroy,
                            formatDouble(scenario.postDestroyHeapMiB)
                        )
                        currentScenarioIndex++
                        if (currentScenarioIndex >= scenarioCounts.size) {
                            isFinished = true
                        } else {
                            delayTicksRemaining = 20
                            state = State.BOOT_DELAY
                            currentScenario = null
                        }
                    }
                }
            }
        }

        fun toJson(): String {
            val builder = StringBuilder()
            builder.append("{\n")
            builder.append("  \"templateId\": \"").append(templateId).append("\",\n")
            builder.append("  \"steadyTicks\": ").append(steadyTicks).append(",\n")
            builder.append("  \"c2meLoaded\": ").append(C2meCompat.isLoaded()).append(",\n")
            builder.append("  \"profile\": \"").append(C2meCompat.profileName()).append("\",\n")
            builder.append("  \"results\": [\n")
            results.forEachIndexed { index, result ->
                builder.append("    {\n")
                builder.append("      \"instanceCount\": ").append(result.instanceCount).append(",\n")
                builder.append("      \"createMs\": ").append(formatDouble(result.createMs)).append(",\n")
                builder.append("      \"steadyAvgTickMs\": ").append(formatDouble(result.steadyAvgTickMs)).append(",\n")
                builder.append("      \"steadyMaxTickMs\": ").append(formatDouble(result.steadyMaxTickMs)).append(",\n")
                builder.append("      \"destroyMs\": ").append(formatDouble(result.destroyMs)).append(",\n")
                builder.append("      \"ticksToReady\": ").append(result.ticksToReady).append(",\n")
                builder.append("      \"ticksToDestroy\": ").append(result.ticksToDestroy).append(",\n")
                builder.append("      \"readyHeapMiB\": ").append(formatDouble(result.readyHeapMiB)).append(",\n")
                builder.append("      \"postDestroyHeapMiB\": ").append(formatDouble(result.postDestroyHeapMiB)).append("\n")
                builder.append("    }")
                if (index < results.lastIndex) {
                    builder.append(',')
                }
                builder.append('\n')
            }
            builder.append("  ]\n")
            builder.append("}\n")
            return builder.toString()
        }

        private fun beginCreate(server: MinecraftServer) {
            val count = scenarioCounts[currentScenarioIndex]
            currentScenario = Scenario(instanceCount = count)
            scenarioStartNs = System.nanoTime()
            scenarioStartTick = server.overworld().gameTime
            logger.info("Benchmark scenario {} starting create", count)
            repeat(count) {
                val instance = when (val created = InstanceManager.createInstance(server, templateId)) {
                    is InstanceCreateResult.Accepted -> created.instance
                    is InstanceCreateResult.Rejected -> error("Benchmark instance creation rejected for template '$templateId': ${created.reason}")
                }
                currentScenario!!.instanceIds += instance.id
            }
            state = State.CREATING
        }

        private fun beginDestroy(server: MinecraftServer) {
            val scenario = currentScenario ?: return
            scenarioStartNs = System.nanoTime()
            scenarioStartTick = server.overworld().gameTime
            logger.info("Benchmark scenario {} starting destroy", scenario.instanceCount)
            scenario.instanceIds.forEach { InstanceManager.scheduleDestroy(server, it) }
            state = State.DESTROYING
        }

        private fun instanceReady(server: MinecraftServer, instanceId: UUID): Boolean {
            val handle = InstanceManager.getInstance(instanceId) ?: return false
            return handle.state == InstanceState.ACTIVE &&
                InstanceManager.isTravelReady(instanceId) &&
                server.getLevel(handle.levelKey) != null
        }

        private fun heapUsageMiB(): Double {
            val runtime = Runtime.getRuntime()
            return (runtime.totalMemory() - runtime.freeMemory()).toDouble() / (1024.0 * 1024.0)
        }

        private fun nanosToMs(value: Long): Double = value.toDouble() / 1_000_000.0

        private fun nanosToMs(value: Double): Double = value / 1_000_000.0

        private enum class State {
            BOOT_DELAY,
            CREATING,
            STEADY_STATE,
            DESTROYING
        }

        private data class Scenario(
            val instanceCount: Int,
            val instanceIds: MutableList<UUID> = mutableListOf(),
            var createMs: Double = 0.0,
            var steadyAvgTickMs: Double = 0.0,
            var steadyMaxTickMs: Double = 0.0,
            var destroyMs: Double = 0.0,
            var ticksToReady: Long = 0L,
            var ticksToDestroy: Long = 0L,
            var readyHeapMiB: Double = 0.0,
            var postDestroyHeapMiB: Double = 0.0
        ) {
            fun toResult(): ScenarioResult = ScenarioResult(
                instanceCount = instanceCount,
                createMs = createMs,
                steadyAvgTickMs = steadyAvgTickMs,
                steadyMaxTickMs = steadyMaxTickMs,
                destroyMs = destroyMs,
                ticksToReady = ticksToReady,
                ticksToDestroy = ticksToDestroy,
                readyHeapMiB = readyHeapMiB,
                postDestroyHeapMiB = postDestroyHeapMiB
            )
        }
    }

    data class ScenarioResult(
        val instanceCount: Int,
        val createMs: Double,
        val steadyAvgTickMs: Double,
        val steadyMaxTickMs: Double,
        val destroyMs: Double,
        val ticksToReady: Long,
        val ticksToDestroy: Long,
        val readyHeapMiB: Double,
        val postDestroyHeapMiB: Double
    )

}
