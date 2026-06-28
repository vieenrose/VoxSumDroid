package studio.voxsum

import org.junit.Assert.assertEquals
import org.junit.Test
import studio.voxsum.core.config.TranscriptionConfig
import studio.voxsum.core.models.LlmRegistry

/**
 * Guards the bug an on-device re-summarize surfaced: the config's default summary model was hardcoded
 * to Gemma and silently diverged from the registry's default after it changed to Qwen3-0.6B — so the
 * "recommended" model in Settings and the model that actually ran on a fresh install disagreed.
 * The fresh-install defaults MUST point at the registry's recommended model.
 */
class ConfigDefaultsTest {

    @Test fun summaryModelDefaultTracksRegistryDefault() {
        assertEquals(LlmRegistry.DEFAULT_ID, TranscriptionConfig().llmModelId)
    }

    @Test fun defaultModelExistsInRegistry() {
        assertEquals(LlmRegistry.DEFAULT_ID, LlmRegistry.byId(TranscriptionConfig().llmModelId).id)
    }
}
