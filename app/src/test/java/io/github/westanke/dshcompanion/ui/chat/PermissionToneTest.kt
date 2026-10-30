package io.github.westanke.dshcompanion.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [permissionTone] 档位→语义色调的回归测试。
 *
 * 三档权限分色（只读蓝 / 工作区写入琥珀 / 完整访问红）是 2026-10-30 用户明确要求，
 * 颜色本体在 @Composable 层（取主题语义色）不可单测，这里钉死档位映射本身：
 * 任何档位串漏映射都会立刻红灯。
 */
class PermissionToneTest {

    @Test
    fun mapsKnownTiers() {
        assertEquals(PermissionTone.INFO, permissionTone("read-only"))
        assertEquals(PermissionTone.WARN, permissionTone("workspace-write"))
        assertEquals(PermissionTone.DANGER, permissionTone("danger-full-access"))
    }

    @Test
    fun unknownAndNullFallBackToNeutral() {
        assertEquals(PermissionTone.NEUTRAL, permissionTone("custom"))
        assertEquals(PermissionTone.NEUTRAL, permissionTone("some-future-tier"))
        assertEquals(PermissionTone.NEUTRAL, permissionTone(null))
        assertEquals(PermissionTone.NEUTRAL, permissionTone(""))
    }
}
