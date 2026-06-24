package com.agentsflex.audio.volc.protocol;


public enum EventType {
    // Default event
    NONE(0),

    // Upstream Connection events (1-49)
    START_CONNECTION(1),
    START_TASK(1), // Alias of StartConnection
    FINISH_CONNECTION(2),
    FINISH_TASK(2), // Alias of FinishConnection

    // 50 ~ 99 Downstream Connection events
    CONNECTION_STARTED(50), // Connection established successfully
    TASK_STARTED(50), // Alias of ConnectionStarted
    CONNECTION_FAILED(51), // Connection failed (possibly due to authentication failure)
    TASK_FAILED(51), // Alias of ConnectionFailed
    CONNECTION_FINISHED(52), // Connection ended
    TASK_FINISHED(52), // Alias of ConnectionFinished

    // Upstream Session events (100-149)
    START_SESSION(100),
    CANCEL_SESSION(101),
    FINISH_SESSION(102),

    // Downstream Session events (150-199)
    SESSION_STARTED(150),
    SESSION_CANCELED(151),
    SESSION_FINISHED(152),
    SESSION_FAILED(153),
    USAGE_RESPONSE(154), // Usage response
    CHARGE_DATA(154), // Alias of UsageResponse

    // Upstream General events (200-249)
    TASK_REQUEST(200),
    UPDATE_CONFIG(201),

    // Downstream General events (250-299)
    AUDIO_MUTED(250),

    // Upstream TTS events (300-349)
    SAY_HELLO(300),

    // Downstream TTS events (350-399)
    TTS_SENTENCE_START(350),
    TTS_SENTENCE_END(351),
    TTS_RESPONSE(352),
    TTS_ENDED(359),
    PODCAST_ROUND_START(360),
    PODCAST_ROUND_RESPONSE(361),
    PODCAST_ROUND_END(362),

    // Downstream ASR events (450-499)
    ASR_INFO(450),
    ASR_RESPONSE(451),
    ASR_ENDED(459),

    // 500 ~ 549 Upstream dialogue events
    CHAT_TTS_TEXT(500), // (Ground-Truth-Alignment) text for speech synthesis

    // Downstream Chat events (550-599)
    CHAT_RESPONSE(550),
    CHAT_ENDED(559),

    // 650 ~ 699 Downstream dialogue events
    // Events for source (original) language subtitle
    SOURCE_SUBTITLE_START(650),
    SOURCE_SUBTITLE_RESPONSE(651),
    SOURCE_SUBTITLE_END(652),

    // Events for target (translation) language subtitle
    TRANSLATION_SUBTITLE_START(653),
    TRANSLATION_SUBTITLE_RESPONSE(654),
    TRANSLATION_SUBTITLE_END(655);

    private final int value;

    EventType(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    public static EventType fromValue(int value) {
        for (EventType type : EventType.values()) {
            if (type.value == value) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown EventType value: " + value);
    }
}
