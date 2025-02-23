package phoenixclient

import kotlinx.coroutines.flow.*
import mu.KotlinLogging

enum class ChannelState {
    JOINING,
    JOINED,
    LEAVING,
    CLOSE,
}


interface Channel {
    val topic: String

    val state: Flow<ChannelState>
    val messages: Flow<IncomingMessage>
    val joinRef: String?

    suspend fun pushNoReply(event: String, payload: Any = emptyPayload): Result<Unit>
    suspend fun pushNoReply(event: String, payload: Any, timeout: Long): Result<Unit>

    suspend fun push(event: String, payload: Any = emptyPayload): Result<Reply>
    suspend fun push(event: String, payload: Any, timeout: Long): Result<Reply>

    suspend fun leave()
}

internal class ChannelImpl(
    override val topic: String,
    override val messages: Flow<IncomingMessage>,

    private val sendToSocket: suspend (
        event: String,
        payload: Any,
        timeout: DynamicTimeout,
        noRepy: Boolean,
    )
    -> Result<IncomingMessage?>,
    private val disposeFromSocket: suspend (topic: String) -> Unit,
    private val defaultTimeout: Long = DEFAULT_TIMEOUT,
) : Channel {
    private val logger = KotlinLogging.logger {}

    private val _state = MutableStateFlow(ChannelState.CLOSE)
    override val state = _state.asStateFlow()

    private var joinPayload: Any? = null
    private var _joinRef: String? = null
    override val joinRef: String?
        get() = _joinRef

    internal val isJoinedOnce: Boolean
        get() {
            return _joinRef != null
        }

    private fun <T> nonJoinedFailure(): Result<T> =
        Result.failure(BadActionException("Pushing message on non-joined channel is not allowed"))

    override suspend fun pushNoReply(event: String, payload: Any): Result<Unit> =
        pushNoReply(event, payload, defaultTimeout)

    override suspend fun pushNoReply(event: String, payload: Any, timeout: Long): Result<Unit> =
        if (isJoinedOnce) {
            sendToSocket(event, payload, timeout.toDynamicTimeout(), true).map { }
        } else {
            nonJoinedFailure()
        }

    override suspend fun push(event: String, payload: Any) =
        push(event, payload, defaultTimeout)

    override suspend fun push(event: String, payload: Any, timeout: Long)
            : Result<Reply> =
        if (isJoinedOnce) {
            sendToSocket(event, payload, timeout.toDynamicTimeout(), false).fold(
                { it!!.toReply() }, { Result.failure(it) }
            )
        } else {
            nonJoinedFailure()
        }

    override suspend fun leave() {
        disposeFromSocket(topic)
    }

    internal fun dirtyClose() {
        logger.debug("Dirty closing channel with topic '$topic'")
        _state.update { ChannelState.CLOSE }
    }

    internal suspend fun close(timeout: Long = defaultTimeout) {
        if (state.value == ChannelState.LEAVING
            || state.value == ChannelState.CLOSE
        ) {
            return
        }

        _state.update { ChannelState.LEAVING }
        _joinRef = null

        push("phx_leave", emptyPayload, timeout)
            .getOrNull()?.let {
                // Don't care about the result.
                _state.update { ChannelState.CLOSE }
            }
    }

    internal suspend fun rejoin(timeout: DynamicTimeout = DEFAULT_REJOIN_TIMEOUT): Result<Channel> =
        if (_joinRef == null) {
            Result.failure(BadActionException("Channel with topic '$topic' was never joined"))
        } else {
            join(joinPayload ?: emptyPayload, timeout)
        }

    internal suspend fun join(
        payload: Any = emptyPayload,
        timeout: DynamicTimeout = DEFAULT_REJOIN_TIMEOUT,
    ): Result<Channel> =
        when (state.value) {
            ChannelState.LEAVING
            -> Result.failure(BadActionException("Channel with topic '$topic' is leaving"))

            ChannelState.JOINING
            -> Result.failure(BadActionException("Channel with topic '$topic' is already joining"))

            ChannelState.JOINED
            -> Result.failure(BadActionException("Channel with topic '$topic' is already joined"))

            else -> {
                _state.update { ChannelState.JOINING }

                joinPayload = payload

                sendToSocket("phx_join", payload, timeout, false).map { it!! }
                    .onSuccess {
                        logger.debug("Channel with topic '$topic' was joined")
                        _state.update { ChannelState.JOINED }
                        _joinRef = it.ref
                    }
                    .onFailure {
                        logger.error("Failed to join channel with '$topic': " + it.stackTraceToString())
                    }
                    .map { this }
            }
        }
}