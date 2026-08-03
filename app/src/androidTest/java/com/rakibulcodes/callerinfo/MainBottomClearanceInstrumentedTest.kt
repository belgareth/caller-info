package com.rakibulcodes.callerinfo

import android.graphics.Rect
import android.view.View
import androidx.core.widget.NestedScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MainBottomClearanceInstrumentedTest {
    @Test
    fun everySectionScrollsItsFinalMeaningfulViewAboveBottomNavigation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            verifySection(scenario, R.id.nav_lookup, R.id.btnShare) { activity ->
                activity.findViewById<View>(R.id.resultLayout).visibility = View.VISIBLE
            }
            verifySection(scenario, R.id.nav_history, R.id.historyEmptyMessage) { activity ->
                activity.findViewById<View>(R.id.rvHistory).visibility = View.GONE
                activity.findViewById<View>(R.id.emptyState).visibility = View.VISIBLE
            }
            verifySection(scenario, R.id.nav_settings, R.id.settingsFinalCard)
            verifySection(scenario, R.id.nav_info, R.id.developerCard)

            scenario.onActivity { activity ->
                assertActionAvailable(activity.findViewById(R.id.btnShare))
                assertActionAvailable(activity.findViewById(R.id.btnInfoGithub))
            }
        }
    }

    @Test
    fun clearanceTracksMeasuredGeometryWithoutGrowingAcrossNavigationOrRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitLayout(scenario)
            val initial = measuredClearance(scenario)

            repeat(3) {
                listOf(R.id.nav_history, R.id.nav_settings, R.id.nav_info, R.id.nav_lookup).forEach { itemId ->
                    selectSection(scenario, itemId)
                }
            }
            assertEquals(initial, measuredClearance(scenario))

            scenario.recreate()
            awaitLayout(scenario)
            assertEquals(initial, measuredClearance(scenario))
        }
    }

    private fun verifySection(
        scenario: ActivityScenario<MainActivity>,
        navigationItemId: Int,
        finalViewId: Int,
        prepare: (MainActivity) -> Unit = {}
    ) {
        selectSection(scenario, navigationItemId, prepare)
        scrollToBottom(scenario)

        scenario.onActivity { activity ->
            val finalView = activity.findViewById<View>(finalViewId)
            val navigation = activity.findViewById<View>(R.id.bottomNavigationContainer)
            val finalBounds = Rect()
            val navigationBounds = Rect()
            val finalVisible = finalView.getGlobalVisibleRect(finalBounds)
            val navigationVisible = navigation.getGlobalVisibleRect(navigationBounds)
            val minimumSpacing = (12f * activity.resources.displayMetrics.density).toInt()
            val finalLocation = IntArray(2).also(finalView::getLocationOnScreen)
            val scrollView = activity.findViewById<NestedScrollView>(R.id.mainScrollView)
            val diagnostics = "visibility=${finalView.visibility} shown=${finalView.isShown} " +
                "alpha=${finalView.alpha} location=${finalLocation.contentToString()} " +
                "size=${finalView.width}x${finalView.height} scrollY=${scrollView.scrollY} " +
                "scrollHeight=${scrollView.height} contentHeight=${scrollView.getChildAt(0).height}"

            assertTrue("Final view $finalViewId must be visible; $diagnostics", finalVisible)
            assertTrue("Bottom navigation must be visible", navigationVisible)
            assertEquals("Final view $finalViewId must be fully visible", finalView.height, finalBounds.height())
            assertTrue(
                "Final view $finalViewId must clear navigation by at least $minimumSpacing px; " +
                    "final=$finalBounds navigation=$navigationBounds",
                finalBounds.bottom <= navigationBounds.top - minimumSpacing
            )
        }
    }

    private fun selectSection(
        scenario: ActivityScenario<MainActivity>,
        itemId: Int,
        prepare: (MainActivity) -> Unit = {}
    ) {
        scenario.onActivity { activity ->
            activity.findViewById<BottomNavigationView>(R.id.bottomNavigation).selectedItemId = itemId
            prepare(activity)
            val selectedLayoutId = when (itemId) {
                R.id.nav_lookup -> R.id.searchLayout
                R.id.nav_history -> R.id.historyLayout
                R.id.nav_settings -> R.id.settingsLayout
                else -> R.id.infoLayout
            }
            listOf(R.id.searchLayout, R.id.historyLayout, R.id.settingsLayout, R.id.infoLayout).forEach { id ->
                activity.findViewById<View>(id).apply {
                    animate().cancel()
                    if (id == selectedLayoutId) {
                        visibility = View.VISIBLE
                        alpha = 1f
                        translationX = 0f
                        translationY = 0f
                    } else {
                        visibility = View.GONE
                    }
                }
            }
            activity.findViewById<View>(R.id.mainRoot).requestLayout()
        }
        awaitLayout(scenario)
    }

    private fun scrollToBottom(scenario: ActivityScenario<MainActivity>) {
        val latch = CountDownLatch(1)
        scenario.onActivity { activity ->
            val scrollView = activity.findViewById<NestedScrollView>(R.id.mainScrollView)
            scrollView.post {
                scrollView.scrollTo(0, scrollView.getChildAt(0).height)
                scrollView.post { latch.countDown() }
            }
        }
        assertTrue("Timed out scrolling main content", latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun awaitLayout(scenario: ActivityScenario<MainActivity>) {
        val latch = CountDownLatch(1)
        scenario.onActivity { activity ->
            val root = activity.findViewById<View>(R.id.mainRoot)
            root.post {
                root.requestLayout()
                root.post { latch.countDown() }
            }
        }
        assertTrue("Timed out waiting for MainActivity layout", latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun measuredClearance(scenario: ActivityScenario<MainActivity>): Int {
        var actualPadding = -1
        var expectedPadding = -1
        scenario.onActivity { activity ->
            val root = activity.findViewById<View>(R.id.mainRoot)
            val navigation = activity.findViewById<View>(R.id.bottomNavigationContainer)
            val content = activity.findViewById<View>(R.id.mainContentContainer)
            val spacing = (16f * activity.resources.displayMetrics.density).toInt()
            actualPadding = content.paddingBottom
            expectedPadding = BottomNavigationClearance.requiredBottomPadding(
                rootHeight = root.height,
                bottomNavigationTop = navigation.top,
                stableContentBottomPadding = 0,
                readableSpacing = spacing
            )
        }
        assertEquals("Padding must match measured bottom obstruction", expectedPadding, actualPadding)
        return actualPadding
    }

    private fun assertActionAvailable(view: View) {
        assertTrue("Final action must be visible", view.visibility == View.VISIBLE)
        assertTrue("Final action must be enabled", view.isEnabled)
        assertTrue("Final action must remain clickable", view.isClickable && view.hasOnClickListeners())
    }

    private companion object {
        const val TIMEOUT_SECONDS = 10L
    }
}
