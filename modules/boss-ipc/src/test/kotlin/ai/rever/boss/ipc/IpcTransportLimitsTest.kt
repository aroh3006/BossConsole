package ai.rever.boss.ipc

import ai.rever.boss.ipc.proto.StateServiceGrpcKt
import ai.rever.boss.ipc.proto.StateUpdate
import ai.rever.boss.ipc.services.StateServiceImpl
import com.google.protobuf.ByteString
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Authentication proves who a caller is; it does not stop a well-formed-but-abusive local peer
 * from exhausting the other side with well-formed traffic. Every process on the machine can
 * open a socket to the kernel endpoint, so [IpcAddressResolver.configureServerBuilder] and
 * [IpcAddressResolver.configureChannelBuilder] cap the inbound message size on both the server
 * and the client builder, rather than trusting size limits nobody actually configured.
 *
 * This test shrinks [IpcAddressResolver.maxInboundMessageBytes] to a few hundred bytes so it
 * can prove the cap end-to-end - a real client, a real server, a real oversized message - without
 * needing to push tens of megabytes through a unit test. The production default (64 MiB) is
 * restored afterward since the object is a process-wide singleton shared with every other test.
 */
class IpcTransportLimitsTest {
    private val defaultMaxInboundMessageBytes = IpcAddressResolver.maxInboundMessageBytes
    private lateinit var testServer: IpcTestServer
    private lateinit var channel: io.grpc.ManagedChannel

    @Before
    fun setUp() {
        IpcAddressResolver.maxInboundMessageBytes = SMALL_LIMIT_BYTES
        testServer = IpcTestServer(StateServiceImpl())
        channel = testServer.channelFor("test-process")
    }

    @After
    fun tearDown() {
        testServer.close()
        IpcAddressResolver.maxInboundMessageBytes = defaultMaxInboundMessageBytes
    }

    @Test
    fun `a message under the cap is accepted`() =
        runBlocking {
            val stub = StateServiceGrpcKt.StateServiceCoroutineStub(channel)

            val result =
                stub.setState(
                    StateUpdate
                        .newBuilder()
                        .setKey("under.cap")
                        .setValue(ByteString.copyFrom(ByteArray(SMALL_LIMIT_BYTES / 4)))
                        .setValueType("bytes")
                        .setSourceProcess("test-process")
                        .build(),
                )

            assertEquals("under.cap", result.key)
        }

    @Test
    fun `a message over the cap is refused rather than read to completion`() =
        runBlocking {
            val stub = StateServiceGrpcKt.StateServiceCoroutineStub(channel)
            // The proto envelope around the raw bytes pushes the actual wire size a little past
            // the payload size, so this margin is comfortably over maxInboundMessageBytes.
            val oversized = ByteArray(SMALL_LIMIT_BYTES * 4)

            val error =
                assertFailsWith<Exception> {
                    stub.setState(
                        StateUpdate
                            .newBuilder()
                            .setKey("over.cap")
                            .setValue(ByteString.copyFrom(oversized))
                            .setValueType("bytes")
                            .setSourceProcess("test-process")
                            .build(),
                    )
                }
            // Both are how gRPC-Kotlin can surface a rejected call, depending on which side
            // (client-proactive vs. server) declines it first.
            assert(error is StatusException || error is StatusRuntimeException) {
                "expected a gRPC status failure, got: $error"
            }
        }

    private companion object {
        const val SMALL_LIMIT_BYTES = 4096
    }
}
