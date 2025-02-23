package phoenixclient

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test

class ReplyMessage(val message: String)

class ChannelTest {

    @Test
    @ExperimentalCoroutinesApi
    fun testChannelJoinAndRef() = runTest {
        withContext(Dispatchers.Default) {
            withTimeout(5000) {
                val client = getClient()
                var message1: Reply? = null
                var message2: IncomingMessage? = null

                val job1 = launch {
                    client.state.waitConnected()

                    message1 = client
                        .join("test:1").getOrThrow()
                        .push("hello", TestPayload(name = "toto")).getOrThrow()
                }

                val job2 = launch {
                    client.messages.collect { message2 = it }
                }

                client.connect(mapOf("token" to "user1234"))

                job1.join()
                job2.cancel()
                client.disconnect()

                assert(message1?.convertTo(ReplyMessage::class)?.getOrNull()?.message == "hello toto")
                assert(Regex("""^\d+$""").matches(message2?.ref!!))
            }
        }
    }

    @Test
    @ExperimentalCoroutinesApi
    fun testChannelDisconnection() = runTest {
        withContext(Dispatchers.Default) {
            withTimeout(5000) {
                val client = getClient()
                var connection = 0

                client.connect(mapOf("token" to "user1234"))
                val channel = client.join("test:1").getOrThrow()

                val disconnectionJob = launch {
                    client.state.isConnected()
                        .takeWhile { connection < 3 }
                        .collect {
                            connection++
                            launch {
                                channel.pushNoReply("close_socket")
                            }
                        }
                }

                disconnectionJob.join()
                client.disconnect()

                assert(connection == 3)
            }
        }
    }

    @RepeatedTest(10)
    @ExperimentalCoroutinesApi
    fun testChannelCrashRejoin() = runTest {
        withContext(Dispatchers.Default) {
            withTimeout(5000) {
                val client = getClient()
                var countJoined = 0

                val job = launch {
                    client.state.waitConnected()
                    val channel = client.join("test:1").getOrThrow()

                    channel.state
                        .takeWhile { countJoined < 2 }
                        .filter { it == ChannelState.JOINED }
                        .collect {
                            countJoined++
                            channel.pushNoReply("crash_channel")
                        }
                }

                client.connect(mapOf("token" to "user1234"))
                job.join()
                client.disconnect()

                assert(countJoined == 2)
            }
        }
    }

    @RepeatedTest(10)
    @ExperimentalCoroutinesApi
    fun testChannelWrongChannel() = runTest {
        withContext(Dispatchers.Default) {
            var joinRef2: String? = null
            var resultRef: String? = null

            withTimeout(5000) {
                val client = getClient()

                val job1 = launch {
                    client.state.waitConnected()
                    val channel = client.join("test:1").getOrThrow()
                    val joinRef1 = channel.joinRef

                    assert(channel.push("wrong_request").exceptionOrNull() is ChannelException)

                    assert(channel.push("hello", TestPayload(name = "toto"), 100L).isSuccess)
                    joinRef2 = channel.joinRef
                    assert(joinRef1 != null && joinRef2 != null && joinRef1 != joinRef2)
                }

                val job2 = launch {
                    data class Response(val message: String)

                    resultRef = client.messages
                        .filter { message ->
                            val payload = message.payload?.convertTo(Response::class, "response")?.getOrNull()
                            payload?.message == "hello toto"
                        }
                        .map { it.joinRef }
                        .first()
                }

                client.connect(mapOf("token" to "user1234"))
                job1.join()
                job2.join()

                assert(joinRef2 == resultRef)

                client.disconnect()
            }
        }
    }

    @RepeatedTest(10)
    @ExperimentalCoroutinesApi
    fun testChannelBatch() = runTest {
        withContext(Dispatchers.Default) {
            withTimeout(10000) {
                val client = getClient()
                var counter1 = 0
                var counter2 = 0
                val size = 1000

                val job1 = launch {
                    client.state.waitConnected()
                    val channel = client.join("test:1").getOrThrow()

                    repeat(size) {
                        val name = "toto$counter1"
                        channel.push("hello", TestPayload(name = name), 100L).getOrThrow()
                        counter1++
                    }
                }

                val job2 = launch {
                    client.messages
                        .collect {
                            if (it.topic == "test:1" && it.joinRef != null) {
                                counter2++
                                if (counter2 == size) {
                                    cancel()
                                }
                            }
                        }
                }

                client.connect(mapOf("token" to "user1234"))
                joinAll(job1, job2)
                client.disconnect()
                println("counter1: $counter1 counter2: $counter2")

                assert(counter1 == size)
                assert(counter2 == size)
            }
        }
    }

    @Test
    @ExperimentalCoroutinesApi
    fun testChannelMessages() = runTest {
        withContext(Dispatchers.Default) {
            withTimeout(10000) {
                val client = getClient()
                var reply: Reply? = null

                val job = launch {
                    client.state.waitConnected()
                    val channel = client.join("test:1").getOrThrow()
                    reply = channel.push("hello", TestPayload(name = "toto"), 100L).getOrThrow()
                }

                client.connect(mapOf("token" to "user1234"))
                job.join()
                client.disconnect()

                assert(
                    reply?.convertTo(ReplyMessage::class)?.getOrNull()?.message == "hello toto"
                )
            }
        }
    }

    private fun getClient(
        retry: DynamicTimeout = DEFAULT_RETRY,
        heartbeatInterval: Long = DEFAULT_HEARTBEAT_INTERVAL
    ): Client =
        okHttpPhoenixClient(
            port = 4000,
            ssl = false,
            retryTimeout = retry,
            heartbeatInterval = heartbeatInterval,
        ).getOrThrow()
}