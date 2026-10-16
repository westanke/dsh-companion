package io.github.westanke.dshcompanion

import android.content.res.Configuration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class ConnectScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    @OptIn(ExperimentalTestApi::class)
    fun firstLaunchUsesChineseAndShowsConnectionWorkflow() {
        val title = compose.activity.getString(R.string.connect_title)
        compose.waitUntilAtLeastOneExists(hasText(title), timeoutMillis = 5_000)
        assertTrue(title.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN })
        compose.onNodeWithText(title).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.server_address)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.test_connection)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.connect)).performScrollTo().assertIsDisplayed()

        compose.onNodeWithContentDescription(compose.activity.getString(R.string.language))
            .performClick()
        // v2.2.41：原本这里硬编码 "Connect to DeepSeek Harness" 字面量 —— 应用名一改就必然红。
        // 改成读资源，但仍**不用 activity.getString**：语言切换走 AppCompatDelegate.setApplicationLocales，
        // 会触发 Activity 重建，切换瞬间 activity.getString 仍可能拿到切换前的 locale。
        // 这里显式按 en 配置开一个 Context 读 values-en，取值与重建时机无关。
        val englishTitle = compose.activity.resources.createConfigurationContext(
            Configuration(compose.activity.resources.configuration).apply {
                setLocale(Locale.forLanguageTag("en"))
            },
        ).getString(R.string.connect_title)
        compose.waitUntilAtLeastOneExists(hasText(englishTitle), timeoutMillis = 5_000)
        compose.onNodeWithText(englishTitle).assertIsDisplayed()
        compose.onNodeWithContentDescription("Language").performClick()
        compose.waitUntilAtLeastOneExists(hasText(title), timeoutMillis = 5_000)
    }
}
