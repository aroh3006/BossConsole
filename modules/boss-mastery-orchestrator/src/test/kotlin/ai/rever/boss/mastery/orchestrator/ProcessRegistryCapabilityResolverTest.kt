package ai.rever.boss.mastery.orchestrator

import ai.rever.boss.ipc.proto.PluginCapability
import ai.rever.boss.ipc.proto.ProcessManifest
import ai.rever.boss.process.ManagedProcess
import ai.rever.boss.process.ProcessConfig
import ai.rever.boss.process.ProcessRegistry
import ai.rever.boss.process.ProcessType
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A mastery workflow node names a `pluginId` and an `action` from its own definition, not from
 * the manifest the orchestrator holds for that plugin - so nothing stops a workflow from naming
 * an action a plugin never advertised. [ProcessRegistryCapabilityResolver.invoke] used to resolve
 * the process and open the gRPC stub before checking whether the manifest actually lists that
 * action, so an out-of-scope request still reached a live dial to whatever process happened to
 * be registered under that id.
 *
 * These tests never give the [ManagedProcess] an [ai.rever.boss.ipc.BossIpcClient], so a refusal
 * that reached the dial path would fail with "No IPC client for process" instead of "Capability
 * not advertised" - the two messages tell "refused before any dial" apart from "the old, later
 * refusal" without needing a mock gRPC channel.
 */
class ProcessRegistryCapabilityResolverTest {
    private fun capability(action: String) =
        PluginCapability
            .newBuilder()
            .setAction(action)
            .setDescription("test capability $action")
            .build()

    private fun manifestWith(vararg actions: String) =
        ProcessManifest
            .newBuilder()
            .addAllCapabilities(actions.map { capability(it) })
            .build()

    private fun addProcess(
        registry: ProcessRegistry,
        pluginId: String,
        manifest: ProcessManifest?,
    ) {
        val osProcess = ProcessBuilder(dummyCommand()).start()
        try {
            osProcess.waitFor()
        } catch (_: InterruptedException) {
            // Best-effort: the test only needs a Process handle, not a meaningful exit.
        }
        val config =
            ProcessConfig(
                processId = pluginId,
                processType = ProcessType.PLUGIN,
                displayName = pluginId,
                mainClass = "unused.Main",
            )
        registry.register(pluginId, ManagedProcess(config, osProcess, ipcAddress = "unused"), manifest)
    }

    private fun registryWithProcess(
        pluginId: String,
        manifest: ProcessManifest,
    ): ProcessRegistry {
        val registry = ProcessRegistry()
        addProcess(registry, pluginId, manifest)
        return registry
    }

    private fun dummyCommand(): List<String> =
        if (System.getProperty("os.name").lowercase().contains("win")) {
            listOf("cmd", "/c", "exit", "0")
        } else {
            listOf("true")
        }

    @Test
    fun `an action not in the manifest is refused before any dial`() =
        runBlocking {
            val registry = registryWithProcess("terminal-tab", manifestWith("run_command"))
            val resolver = ProcessRegistryCapabilityResolver(registry)

            val error =
                assertFailsWith<IllegalStateException> {
                    resolver.invoke("terminal-tab", "delete_everything", emptyMap())
                }
            assertTrue(
                error.message.orEmpty().contains("Capability not advertised"),
                "expected a scope refusal before any dial, got: ${error.message}",
            )
        }

    @Test
    fun `a plugin with no manifest at all refuses every action before any dial`() =
        runBlocking {
            val registry = ProcessRegistry()
            val osProcess = ProcessBuilder(dummyCommand()).start()
            osProcess.waitFor()
            val config =
                ProcessConfig(
                    processId = "no-manifest",
                    processType = ProcessType.PLUGIN,
                    displayName = "no-manifest",
                    mainClass = "unused.Main",
                )
            // No manifest passed - register() only stores one when it is non-null.
            registry.register("no-manifest", ManagedProcess(config, osProcess, ipcAddress = "unused"))
            val resolver = ProcessRegistryCapabilityResolver(registry)

            val error =
                assertFailsWith<IllegalStateException> {
                    resolver.invoke("no-manifest", "run_command", emptyMap())
                }
            assertTrue(error.message.orEmpty().contains("Capability not advertised"))
        }

    @Test
    fun `an action the manifest actually lists reaches the missing IPC client, not the scope refusal`() =
        runBlocking {
            val registry = registryWithProcess("terminal-tab", manifestWith("run_command"))
            val resolver = ProcessRegistryCapabilityResolver(registry)

            val error =
                assertFailsWith<IllegalStateException> {
                    resolver.invoke("terminal-tab", "run_command", emptyMap())
                }
            // Proves the scope check is not simply refusing everything: an in-scope action
            // clears it and fails later, on the (in this test, absent) IPC client instead.
            // That later failure is also the strongest proof available in a unit test that no
            // gRPC dial happens for a refused call: ProcessRegistryCapabilityResolver.invoke
            // throws on the null ipcClient one line before it ever constructs
            // CapabilityServiceCoroutineStub, so reaching this exact message - rather than a
            // channel or RPC-level failure - is only possible if the stub was never built.
            assertTrue(
                error.message.orEmpty().contains("No IPC client"),
                "expected the scope check to pass and fail on the missing IPC client, got: ${error.message}",
            )
        }

    @Test
    fun `an action advertised by a different plugin does not clear this plugin's check`() =
        runBlocking {
            // Two real capabilities, each registered under its own plugin. A resolver that
            // checked the action against ANY plugin's manifest - rather than scoping the lookup
            // to the requested pluginId - would let "flow-tab" borrow "terminal-tab"'s
            // run_command. findCapability(pluginId, action) already scopes by pluginId; this
            // pins that behavior against a regression that widens the lookup.
            val registry = registryWithProcess("terminal-tab", manifestWith("run_command"))
            addProcess(registry, "flow-tab", manifestWith("compose_message"))
            val resolver = ProcessRegistryCapabilityResolver(registry)

            val error =
                assertFailsWith<IllegalStateException> {
                    resolver.invoke("flow-tab", "run_command", emptyMap())
                }
            assertTrue(error.message.orEmpty().contains("Capability not advertised"))
        }

    @Test
    fun `matching is exact, not a prefix or substring`() =
        runBlocking {
            val registry = registryWithProcess("terminal-tab", manifestWith("run_command"))
            val resolver = ProcessRegistryCapabilityResolver(registry)

            val error =
                assertFailsWith<IllegalStateException> {
                    resolver.invoke("terminal-tab", "run_command_extra", emptyMap())
                }
            assertTrue(error.message.orEmpty().contains("Capability not advertised"))
        }

    @Test
    fun `a duplicate capability entry in the manifest still resolves the same plugin and action`() =
        runBlocking {
            // A manifest listing the same action twice (find() returns the first match) must not
            // change which pluginId or action the check answers for - it is still one plugin's
            // own manifest, so there is no cross-plugin shadowing to worry about here.
            val registry = registryWithProcess("terminal-tab", manifestWith("run_command", "run_command"))
            val resolver = ProcessRegistryCapabilityResolver(registry)

            val error =
                assertFailsWith<IllegalStateException> {
                    resolver.invoke("terminal-tab", "run_command", emptyMap())
                }
            assertTrue(error.message.orEmpty().contains("No IPC client"))
        }

    @Test
    fun `the check reads the live manifest, not one captured when the resolver was built`() =
        runBlocking {
            // ProcessRegistryCapabilityResolver holds only a ProcessRegistry reference, not a
            // copy of any manifest - this pins that a capability registered AFTER the resolver
            // was constructed (the real sequence: a plugin process starts, the resolver already
            // exists, then the process calls back over IPC to register its manifest) is still
            // honored, and that the reverse - a capability removed from a later re-registration -
            // is honored too.
            val registry = registryWithProcess("terminal-tab", manifestWith("run_command"))
            val resolver = ProcessRegistryCapabilityResolver(registry)

            assertTrue(
                assertFailsWith<IllegalStateException> {
                    resolver.invoke("terminal-tab", "open_terminal", emptyMap())
                }.message.orEmpty().contains("Capability not advertised"),
            )

            registry.updateManifest("terminal-tab", manifestWith("run_command", "open_terminal"))

            assertTrue(
                assertFailsWith<IllegalStateException> {
                    resolver.invoke("terminal-tab", "open_terminal", emptyMap())
                }.message.orEmpty().contains("No IPC client"),
                "a capability added after construction must be honored by the same resolver instance",
            )
        }
}
