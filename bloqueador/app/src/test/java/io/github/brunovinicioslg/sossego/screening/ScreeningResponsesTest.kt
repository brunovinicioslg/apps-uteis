package io.github.brunovinicioslg.sossego.screening

import com.google.common.truth.Truth.assertThat
import io.github.brunovinicioslg.sossego.core.rules.BlockAction
import io.github.brunovinicioslg.sossego.core.rules.Decision
import io.github.brunovinicioslg.sossego.core.rules.Reason
import io.github.brunovinicioslg.sossego.core.rules.Settings
import org.junit.Test

class ScreeningResponsesTest {

    private fun plan(reason: Reason, settings: Settings = Settings()) = ScreeningResponses.plan(Verdict(Decision(reason), settings))

    @Test
    fun `calls that get through are left alone`() {
        for (reason in Reason.entries.filter { !it.blocks }) {
            assertThat(plan(reason)).isEqualTo(ResponsePlan())
        }
    }

    @Test
    fun `a declined call does not ring nor show as missed`() {
        val plan = plan(Reason.BLOCK_LISTED)
        assertThat(plan.disallow).isTrue()
        assertThat(plan.reject).isTrue()
        assertThat(plan.skipNotification).isTrue()
        assertThat(plan.silence).isFalse()
    }

    @Test
    fun `the phone's history keeps declined calls unless turned off`() {
        assertThat(plan(Reason.BLOCK_LISTED).skipCallLog).isFalse()
        assertThat(plan(Reason.BLOCK_LISTED, Settings(keepInCallLog = false)).skipCallLog).isTrue()
    }

    @Test
    fun `a silenced call gets through without sound`() {
        val plan = plan(Reason.NOT_ALLOWED, Settings(action = BlockAction.SILENCE))
        assertThat(plan).isEqualTo(ResponsePlan(silence = true))
    }
}
