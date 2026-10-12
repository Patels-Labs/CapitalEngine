package com.patelheet.capitalengine.core;

import com.patelheet.capitalengine.api.CapitalApi;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/** Loads the real CapitalEngine plugin into MockBukkit (with a fake "Vault" present so the Vault providers register). */
public abstract class PluginTestBase {
    public ServerMock server;
    public CapitalEnginePlugin core;
    public CapitalApi api;
    public JavaPlugin other;

    @BeforeEach
    void startServer() {
        server = MockBukkit.mock();
        MockBukkit.createMockPlugin("Vault");
        core = MockBukkit.load(CapitalEnginePlugin.class);
        api = server.getServicesManager().load(CapitalApi.class);
        other = MockBukkit.createMockPlugin("Other");
    }

    @AfterEach
    void stopServer() { MockBukkit.unmock(); }

    public static <T> T await(CompletableFuture<T> f) throws Exception { return f.get(5, TimeUnit.SECONDS); }
}
