package com.bettercontent.dimensiondrink.gametest;

import com.google.gson.GsonBuilder;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestRegistry;
import net.minecraft.gametest.framework.GlobalTestReporter;
import net.minecraft.gametest.framework.LogTestReporter;
import net.minecraft.gametest.framework.TestReporter;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Emits actual runtime discovery and completion evidence for the repository gate. */
@Mod.EventBusSubscriber(modid = "dimension_drink")
public final class ExecutionReporter implements TestReporter {
    private final Path output;
    private final Map<String, Object> report = new LinkedHashMap<>();
    private final List<Map<String, Object>> results = new ArrayList<>();
    private final TestReporter logging = new LogTestReporter();

    private ExecutionReporter(Path output) {
        this.output = output;
        report.put("profile", System.getProperty("dimension_drink.gametest.selection", "all"));
        report.put("runId", System.getProperty("bc.gametest.dimension_drink.runId"));
        report.put("registered", GameTestRegistry.getAllTestFunctions().stream()
            .filter(test -> test.getStructureName().startsWith("dimension_drink:"))
            .map(test -> test.getTestName()).sorted().toList());
        report.put("results", results);
        report.put("finished", false);
        write();
    }

    @SubscribeEvent
    public static void install(ServerAboutToStartEvent event) {
        String destination = System.getProperty("bc.gametest.dimension_drink.report");
        if (destination != null) GlobalTestReporter.replaceWith(new ExecutionReporter(Path.of(destination)));
    }

    @Override public void onTestFailed(GameTestInfo test) {
        logging.onTestFailed(test);
        record(test, "failed");
    }

    @Override public void onTestSuccess(GameTestInfo test) {
        logging.onTestSuccess(test);
        record(test, "passed");
    }

    private void record(GameTestInfo test, String status) {
        if (!test.getStructureName().startsWith("dimension_drink:")) return;
        var result = new LinkedHashMap<String, Object>();
        result.put("id", test.getTestName());
        result.put("status", status);
        if (test.getError() != null) result.put("error", test.getError().toString());
        results.add(result);
        write();
    }

    @Override public void finish() {
        logging.finish();
        report.put("finished", true);
        write();
    }

    private void write() {
        try {
            Files.createDirectories(output.getParent());
            Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(report));
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot write GameTest execution evidence to " + output, failure);
        }
    }
}
