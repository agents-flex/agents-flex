package com.agentsflex.core.model.exception;

import com.agentsflex.core.model.chat.response.AiMessageResponse;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ModelErrorClassifierTest {

    @Test
    public void shouldClassifyOpenAiRateLimit() {
        ModelException error = ModelErrorClassifier.fromError(
            429, null, "rate_limit_error", "Too many requests", 2500L);

        assertTrue(error instanceof ModelRateLimitException);
        assertEquals(Long.valueOf(2500L),
            ((ModelRateLimitException) error).getRetryAfterMillis());
    }

    @Test
    public void shouldKeepQuotaErrorsOutOfRateLimitRetryPath() {
        ModelException error = ModelErrorClassifier.fromError(
            429, "organization_spend_limit_exceeded", "insufficient_quota",
            "Monthly spend limit reached", null);

        assertTrue(error instanceof ModelQuotaExceededException);
        assertTrue(!(error instanceof ModelRateLimitException));
    }

    @Test
    public void shouldClassifyContextLengthError() {
        ModelException error = ModelErrorClassifier.fromError(
            400, "invalid_parameter_error", "invalid_request_error",
            "The request exceeds the maximum context length", null);

        assertTrue(error instanceof TokenLimitExceededException);
        assertEquals(TokenLimitExceededException.Phase.INPUT_CONTEXT,
            ((TokenLimitExceededException) error).getPhase());
    }

    @Test
    public void shouldClassifyOverloadedService() {
        ModelException error = ModelErrorClassifier.fromError(
            503, null, null, "The engine is currently overloaded", null);

        assertTrue(error instanceof ModelOverloadedException);
    }

    @Test
    public void shouldParseOpenAiErrorBody() {
        ModelException error = ModelErrorClassifier.fromHttpError(429,
            "{\"error\":{\"code\":\"credit_balance_exhausted\","
                + "\"type\":\"insufficient_quota\",\"message\":\"no credits\"}}",
            null);

        assertTrue(error instanceof ModelQuotaExceededException);
        assertEquals("credit_balance_exhausted",
            ((ModelQuotaExceededException) error).getErrorCode());
    }

    @Test
    public void shouldExposeConversionFromResponse() {
        AiMessageResponse response = AiMessageResponse.error(null, "{}", "context length exceeded");
        response.setErrorType("invalid_request_error");
        response.setErrorCode("context_length_exceeded");

        assertTrue(response.toException() instanceof TokenLimitExceededException);
    }
}
