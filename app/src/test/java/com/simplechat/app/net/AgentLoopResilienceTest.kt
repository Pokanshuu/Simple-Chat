package com.simplechat.app.net

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Resilience of the agent loop against protocol drift in OpenAI-compatible gateways.
 *
 * Every case here used to end the turn with an exception or silently drop the tool call:
 * a gateway-specific stop reason, an oversized batch, a blank/replayed call id, or a repeated
 * function-name delta. The loop must degrade to a structured tool result and keep the answer.
 */
class AgentLoopResilienceTest {

    private fun runtime() = AgentRuntimeState(
        webConfigured = true, webSwitchOn = true, clientSearchEnabled = true,
    )

    @Test
    fun completeToolCallExecutesEvenWhenTheGatewayReportsAnotherStopReason() = runBlocking {
        for (reason in listOf("stop", "function_call", null)) {
            var executed = 0
            var requests = 0
            val events = agentLoop(
                session = null,
                style = ApiStyle.OPENAI,
                nativeSearch = { executed++; AgentToolOutcome.success("evidence") },
                runtime = { runtime() },
            ) { _, _, _ ->
                when (++requests) {
                    1 -> flowOf(
                        StreamEvent.ToolCallDelta(0, "call-1", IndependentSearchName, """{"query":"q"}"""),
                        StreamEvent.Stopped(reason),
                    )
                    else -> flowOf(StreamEvent.Content("answer"), StreamEvent.Stopped("stop"))
                }
            }.toList()
            assertEquals("stop reason=$reason executed", 1, executed)
            assertEquals("stop reason=$reason rounds", 2, requests)
            assertTrue("stop reason=$reason answer", events.any { it is StreamEvent.Content && it.text == "answer" })
        }
    }

    @Test
    fun refusalAndTruncationStillSkipExecution() = runBlocking {
        for (reason in listOf("refusal", "length", "max_tokens", "content_filter")) {
            var executed = 0
            agentLoop(
                session = null,
                style = ApiStyle.OPENAI,
                nativeSearch = { executed++; AgentToolOutcome.success("must not run") },
                runtime = { runtime() },
            ) { _, _, _ ->
                flowOf(
                    StreamEvent.ToolCallDelta(0, "call-1", IndependentSearchName, """{"query":"q"}"""),
                    StreamEvent.Stopped(reason),
                )
            }.toList()
            assertEquals("stop reason=$reason", 0, executed)
        }
    }

    @Test
    fun oversizedCallBatchIsTrimmedInsteadOfAbortingTheTurn() = runBlocking {
        var executed = 0
        var requests = 0
        var statuses: List<String> = emptyList()
        agentLoop(
            session = null,
            style = ApiStyle.OPENAI,
            nativeSearch = { executed++; AgentToolOutcome.success("ok") },
            runtime = { runtime() },
        ) { history, _, _ ->
            if (++requests == 1) {
                val frames = mutableListOf<StreamEvent>()
                repeat(6) { index ->
                    frames += StreamEvent.ToolCallDelta(index, "c$index", IndependentSearchName, """{"query":"q$index"}""")
                }
                frames += StreamEvent.Stopped("tool_calls")
                flowOf(*frames.toTypedArray())
            } else {
                statuses = history.transcript.events
                    .filter { it.kind == AgentTranscriptKind.TOOL_RESULT }
                    .map { checkNotNull(it.status) }
                flowOf(StreamEvent.Content("done"), StreamEvent.Stopped("stop"))
            }
        }.toList()
        // AgentHarnessPolicy.maxToolCallsPerRound == 4
        assertEquals(4, executed)
        assertEquals(2, requests)
        assertEquals(4, statuses.count { it == "success" })
        assertEquals(2, statuses.count { it == "budget_exceeded" })
    }

    @Test
    fun blankAndReplayedCallIdsAreRepairedWithoutLosingTheAnswer() = runBlocking {
        var executed = 0
        var requests = 0
        agentLoop(
            session = null,
            style = ApiStyle.OPENAI,
            nativeSearch = { executed++; AgentToolOutcome.success("ok") },
            runtime = { runtime() },
        ) { history, _, _ ->
            if (++requests == 1) {
                flowOf(
                    StreamEvent.ToolCallDelta(0, "", IndependentSearchName, """{"query":"a"}"""),
                    StreamEvent.ToolCallDelta(1, "same", IndependentSearchName, """{"query":"b"}"""),
                    StreamEvent.ToolCallDelta(2, "same", IndependentSearchName, """{"query":"c"}"""),
                    StreamEvent.Stopped("tool_calls"),
                )
            } else {
                val ids = checkNotNull(history.chat.first().toolCalls)
                    .map { (it as JsonObject)["id"]!!.jsonPrimitive.content }
                assertEquals(3, ids.size)
                assertEquals(3, ids.distinct().size)
                assertTrue(ids.none { it.isBlank() })
                // The replayed call is answered, not executed.
                assertTrue(history.chat.last().content.toString().contains("Replayed"))
                flowOf(StreamEvent.Content("done"), StreamEvent.Stopped("stop"))
            }
        }.toList()
        assertEquals(2, executed)
        assertEquals(2, requests)
    }

    @Test
    fun oversizedArgumentsBecomeInvalidArgumentsInsteadOfACrash() = runBlocking {
        var executed = 0
        var requests = 0
        agentLoop(
            session = null,
            style = ApiStyle.OPENAI,
            nativeSearch = { executed++; AgentToolOutcome.success("ok") },
            runtime = { runtime() },
        ) { history, _, _ ->
            if (++requests == 1) {
                flowOf(
                    StreamEvent.ToolCallDelta(0, "big", IndependentSearchName, "{\"query\":\"" + "x".repeat(9_000) + "\"}"),
                    StreamEvent.Stopped("tool_calls"),
                )
            } else {
                assertTrue(history.chat.last().content.toString().contains("truncated"))
                flowOf(StreamEvent.Content("done"), StreamEvent.Stopped("stop"))
            }
        }.toList()
        assertEquals(0, executed)
        assertEquals(2, requests)
    }

    @Test
    fun repeatedNameDeltasDoNotTurnKnownToolsIntoUnknownOnes() = runBlocking {
        var executed = 0
        var requests = 0
        agentLoop(
            session = null,
            style = ApiStyle.OPENAI,
            nativeSearch = { executed++; AgentToolOutcome.success("ok") },
            runtime = { runtime() },
        ) { _, _, _ ->
            if (++requests == 1) {
                flowOf(
                    StreamEvent.ToolCallDelta(0, "id1", IndependentSearchName, null),
                    StreamEvent.ToolCallDelta(0, null, IndependentSearchName, null),
                    StreamEvent.ToolCallDelta(0, null, IndependentSearchName, """{"query":"q"}"""),
                    StreamEvent.Stopped("tool_calls"),
                )
            } else {
                flowOf(StreamEvent.Content("done"), StreamEvent.Stopped("stop"))
            }
        }.toList()
        assertEquals(1, executed)
        assertEquals(2, requests)
    }

    @Test
    fun toolNameFragmentsAndRepeatsBothResolveToTheWireName() {
        assertEquals("web_search", mergedToolName("web", "_search"))
        assertEquals("web_search", mergedToolName("web_search", "web_search"))
        assertEquals("web_search", mergedToolName("we", "web_search"))
        assertEquals("generate_image", mergedToolName("generate_", "image"))
    }

    @Test
    fun exhaustedWebBudgetIsReportedAsSpentNotUnavailable() {
        val instruction = agentCapabilityInstruction(
            webConfigured = true, webEnabled = false, webFetchEnabled = false,
            imageConfigured = false, imageExposed = false, webSwitchOn = true, webBudgetExhausted = true,
        )
        assertTrue(instruction.contains("web_search=used_this_turn_budget_exhausted"))
        assertFalse(instruction.contains("web_search=unavailable"))
        assertTrue(instruction.contains("budget for this turn is spent"))
    }

    @Test
    fun capabilityDenialRecoversOnceEvenWithoutSwitchPhrasing() = runBlocking {
        var requests = 0
        var retries = 0
        agentLoop(
            session = null,
            style = ApiStyle.OPENAI,
            runtime = { runtime() },
            onSearchAnswerRetry = { retries++ },
        ) { _, _, _ ->
            when (++requests) {
                1 -> flowOf(StreamEvent.Content("我没有联网能力，无法查询实时天气。"), StreamEvent.Stopped("stop"))
                else -> flowOf(StreamEvent.Content("已按当前工具完成查询。"), StreamEvent.Stopped("stop"))
            }
        }.toList()
        // Exactly one recovery round: the denial shape is broader than the switch wording.
        assertEquals(1, retries)
        assertEquals(2, requests)
    }

    @Test
    fun searchSwitchRestartOnlyAppliesBeforeAnyVisibleOutput() {
        // The user turned search on while the request was still thinking: resend with the new state.
        assertTrue(shouldRestartForSearchSwitch(false, true, true, bodyCommitted = false, toolCalled = false))
        // Turning it off is handled by the executor refusing the call, not by a resend.
        assertFalse(shouldRestartForSearchSwitch(true, false, true, bodyCommitted = false, toolCalled = false))
        // Nothing to restart when the app cannot search at all.
        assertFalse(shouldRestartForSearchSwitch(false, true, false, bodyCommitted = false, toolCalled = false))
        // Visible text or an executed tool call must never be retracted.
        assertFalse(shouldRestartForSearchSwitch(false, true, true, bodyCommitted = true, toolCalled = false))
        assertFalse(shouldRestartForSearchSwitch(false, true, true, bodyCommitted = false, toolCalled = true))
        // Unchanged state is a no-op.
        assertFalse(shouldRestartForSearchSwitch(true, true, true, bodyCommitted = false, toolCalled = false))
    }

    @Test
    fun theModelSeesTheSpentBudgetOnTheThirdRound() = runBlocking {
        var requests = 0
        agentLoop(
            session = null,
            style = ApiStyle.OPENAI,
            nativeSearch = { query -> AgentToolOutcome.success("evidence $query") },
            runtime = { runtime() },
        ) { history, offer, _ ->
            when (++requests) {
                1 -> flowOf(
                    StreamEvent.ToolCallDelta(0, "a", IndependentSearchName, """{"query":"q1"}"""),
                    StreamEvent.Stopped("tool_calls"),
                )
                2 -> flowOf(
                    StreamEvent.ToolCallDelta(0, "b", IndependentSearchName, """{"query":"q2"}"""),
                    StreamEvent.Stopped("tool_calls"),
                )
                else -> {
                    assertFalse(offer)
                    assertTrue(history.runtimeInstruction.contains("used_this_turn_budget_exhausted"))
                    flowOf(StreamEvent.Content("done"), StreamEvent.Stopped("stop"))
                }
            }
        }.toList()
        assertEquals(3, requests)
    }
}
