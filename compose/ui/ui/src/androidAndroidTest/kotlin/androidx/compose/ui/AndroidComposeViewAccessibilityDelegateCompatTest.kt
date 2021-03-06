/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.ui

import android.os.Build
import android.text.SpannableString
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.node.InnerPlaceable
import androidx.compose.ui.node.LayoutNode
import androidx.compose.ui.platform.AmbientClipboardManager
import androidx.compose.ui.platform.AndroidComposeView
import androidx.compose.ui.platform.AndroidComposeViewAccessibilityDelegateCompat
import androidx.compose.ui.semantics.AccessibilityRangeInfo
import androidx.compose.ui.semantics.AccessibilityScrollState
import androidx.compose.ui.semantics.SemanticsModifierCore
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.SemanticsWrapper
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.stateDescriptionRange
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.focused
import androidx.compose.ui.semantics.getTextLayoutResult
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.pasteText
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.setSelection
import androidx.compose.ui.semantics.setText
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.textSelectionRange
import androidx.compose.ui.semantics.verticalAccessibilityScrollState
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import com.nhaarman.mockitokotlin2.argThat
import com.nhaarman.mockitokotlin2.doReturn
import com.nhaarman.mockitokotlin2.eq
import com.nhaarman.mockitokotlin2.never
import com.nhaarman.mockitokotlin2.spy
import com.nhaarman.mockitokotlin2.times
import com.nhaarman.mockitokotlin2.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatcher
import org.mockito.ArgumentMatchers

@SmallTest
@RunWith(AndroidJUnit4::class)
class AndroidComposeViewAccessibilityDelegateCompatTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var accessibilityDelegate: AndroidComposeViewAccessibilityDelegateCompat
    private lateinit var container: ViewGroup
    private lateinit var androidComposeView: AndroidComposeView

    @Before
    fun setup() {
        // Use uiAutomation to enable accessibility manager.
        InstrumentationRegistry.getInstrumentation().uiAutomation
        rule.activityRule.scenario.onActivity {
            androidComposeView = AndroidComposeView(it)
            container = spy(FrameLayout(it)) {
                on {
                    onRequestSendAccessibilityEvent(
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any()
                    )
                } doReturn false
            }
            container.addView(androidComposeView)
            accessibilityDelegate = AndroidComposeViewAccessibilityDelegateCompat(
                androidComposeView
            )
        }
        rule.setContent {
            AmbientClipboardManager.current.setText(AnnotatedString("test"))
        }
    }

    @Test
    fun testPopulateAccessibilityNodeInfoProperties_general() {
        val info = AccessibilityNodeInfoCompat.obtain()
        val clickActionLabel = "click"
        val dismissActionLabel = "dismiss"
        val stateDescription = "checked"
        val semanticsModifier = SemanticsModifierCore(1, true, false) {
            this.stateDescription = stateDescription
            onClick(clickActionLabel) { true }
            dismiss(dismissActionLabel) { true }
        }
        val semanticsNode = SemanticsNode(
            SemanticsWrapper(InnerPlaceable(LayoutNode()), semanticsModifier),
            true
        )
        accessibilityDelegate.populateAccessibilityNodeInfoProperties(1, info, semanticsNode)
        assertEquals("android.view.View", info.className)
        assertTrue(
            containsAction(
                info,
                AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                    AccessibilityNodeInfoCompat.ACTION_CLICK,
                    clickActionLabel
                )
            )
        )
        assertTrue(
            containsAction(
                info,
                AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                    AccessibilityNodeInfoCompat.ACTION_DISMISS,
                    dismissActionLabel
                )
            )
        )
        val stateDescriptionResult = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                info.unwrap().stateDescription
            }
            Build.VERSION.SDK_INT >= 19 -> {
                info.extras.getCharSequence(
                    "androidx.view.accessibility.AccessibilityNodeInfoCompat.STATE_DESCRIPTION_KEY"
                )
            }
            else -> {
                null
            }
        }
        assertEquals(stateDescription, stateDescriptionResult)
        assertTrue(info.isClickable)
        assertTrue(info.isVisibleToUser)
    }

    @Test
    fun testPopulateAccessibilityNodeInfoProperties_SeekBar() {
        val info = AccessibilityNodeInfoCompat.obtain()
        val setProgressActionLabel = "setProgress"
        val semanticsModifier = SemanticsModifierCore(1, true, false) {
            stateDescriptionRange = AccessibilityRangeInfo(0.5f, 0f..1f, 6)
            setProgress(setProgressActionLabel) { true }
        }
        val semanticsNode = SemanticsNode(
            SemanticsWrapper(InnerPlaceable(LayoutNode()), semanticsModifier),
            true
        )
        accessibilityDelegate.populateAccessibilityNodeInfoProperties(1, info, semanticsNode)
        assertEquals("android.widget.SeekBar", info.className)
        assertEquals(
            AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT,
            info.rangeInfo.type
        )
        assertEquals(0.5f, info.rangeInfo.current)
        assertEquals(0f, info.rangeInfo.min)
        assertEquals(1f, info.rangeInfo.max)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            assertTrue(
                containsAction(
                    info,
                    AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                        android.R.id.accessibilityActionSetProgress,
                        setProgressActionLabel
                    )
                )
            )
        }
    }

    @Test
    fun testPopulateAccessibilityNodeInfoProperties_EditText() {
        val info = AccessibilityNodeInfoCompat.obtain()
        val setSelectionActionLabel = "setSelection"
        val setTextActionLabel = "setText"
        val text = "hello"
        val semanticsModifier = SemanticsModifierCore(1, true, false) {
            this.text = AnnotatedString(text)
            this.textSelectionRange = TextRange(1)
            this.focused = true
            getTextLayoutResult { true }
            setText(setTextActionLabel) { true }
            setSelection(setSelectionActionLabel) { _, _, _ -> true }
        }
        val semanticsNode = SemanticsNode(
            SemanticsWrapper(InnerPlaceable(LayoutNode()), semanticsModifier),
            true
        )
        accessibilityDelegate.populateAccessibilityNodeInfoProperties(1, info, semanticsNode)
        assertEquals("android.widget.EditText", info.className)
        assertEquals(SpannableString(text), info.text)
        assertTrue(info.isFocusable)
        assertTrue(info.isFocused)
        assertTrue(info.isEditable)
        assertTrue(
            containsAction(
                info,
                AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                    AccessibilityNodeInfoCompat.ACTION_SET_SELECTION,
                    setSelectionActionLabel
                )
            )
        )
        assertTrue(
            containsAction(
                info,
                AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                    AccessibilityNodeInfoCompat.ACTION_SET_TEXT,
                    setTextActionLabel
                )
            )
        )
        assertTrue(
            containsAction(
                info,
                AccessibilityNodeInfoCompat.AccessibilityActionCompat
                    .ACTION_NEXT_AT_MOVEMENT_GRANULARITY
            )
        )
        assertTrue(
            containsAction(
                info,
                AccessibilityNodeInfoCompat.AccessibilityActionCompat
                    .ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY
            )
        )
        assertEquals(
            AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_CHARACTER or
                AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_WORD or
                AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_PARAGRAPH or
                AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_LINE or
                AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_PAGE,
            info.movementGranularities
        )
        if (Build.VERSION.SDK_INT >= 26) {
            assertEquals(
                listOf(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY),
                info.unwrap().availableExtraData
            )
        }
    }

    @Test
    fun test_PasteAction_ifFocused() {
        val info = AccessibilityNodeInfoCompat.obtain()
        val semanticsNode = createSemanticsNodeWithProperties(1, true) {
            focused = true
            pasteText {
                true
            }
        }
        accessibilityDelegate.populateAccessibilityNodeInfoProperties(1, info, semanticsNode)

        assertTrue(info.isFocused)
        assertTrue(
            containsAction(
                info,
                AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                    AccessibilityNodeInfoCompat.ACTION_PASTE,
                    null
                )
            )
        )
    }

    @Test
    fun test_noPasteAction_ifUnfocused() {
        val info = AccessibilityNodeInfoCompat.obtain()
        val semanticsNode = createSemanticsNodeWithProperties(1, true) {
            pasteText {
                true
            }
        }
        accessibilityDelegate.populateAccessibilityNodeInfoProperties(1, info, semanticsNode)

        assertFalse(info.isFocused)
        assertFalse(
            containsAction(
                info,
                AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                    AccessibilityNodeInfoCompat.ACTION_PASTE,
                    null
                )
            )
        )
    }

    @Test
    fun notSendScrollEvent_whenOnlyAccessibilityScrollStateMaxValueChanges() {
        val oldSemanticsNode = createSemanticsNodeWithProperties(1, true) {
            this.verticalAccessibilityScrollState = AccessibilityScrollState(0f, 0f, false)
        }
        accessibilityDelegate.semanticsNodes[1] =
            AndroidComposeViewAccessibilityDelegateCompat.SemanticsNodeCopy(oldSemanticsNode)
        val newSemanticsNode = createSemanticsNodeWithProperties(1, true) {
            this.verticalAccessibilityScrollState = AccessibilityScrollState(0f, 5f, false)
        }
        val newNodes = mutableMapOf<Int, SemanticsNode>()
        newNodes[1] = newSemanticsNode
        accessibilityDelegate.sendSemanticsPropertyChangeEvents(newNodes)

        verify(container, never()).requestSendAccessibilityEvent(
            eq(androidComposeView),
            argThat(
                ArgumentMatcher {
                    it.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
                }
            )
        )
        verify(container, times(1)).requestSendAccessibilityEvent(
            eq(androidComposeView),
            argThat(
                ArgumentMatcher {
                    it.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
                        it.contentChangeTypes == AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE
                }
            )
        )
    }

    @Test
    fun sendScrollEvent_whenAccessibilityScrollStateValueChanges() {
        val oldSemanticsNode = createSemanticsNodeWithProperties(2, false) {
            this.verticalAccessibilityScrollState = AccessibilityScrollState(0f, 5f, false)
        }
        accessibilityDelegate.semanticsNodes[1] =
            AndroidComposeViewAccessibilityDelegateCompat.SemanticsNodeCopy(oldSemanticsNode)
        val newSemanticsNode = createSemanticsNodeWithProperties(2, false) {
            this.verticalAccessibilityScrollState = AccessibilityScrollState(2f, 5f, false)
        }
        val newNodes = mutableMapOf<Int, SemanticsNode>()
        newNodes[1] = newSemanticsNode
        accessibilityDelegate.sendSemanticsPropertyChangeEvents(newNodes)

        verify(container, times(1)).requestSendAccessibilityEvent(
            eq(androidComposeView),
            argThat(
                ArgumentMatcher {
                    it.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
                        it.contentChangeTypes == AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE
                }
            )
        )
        verify(container, times(1)).requestSendAccessibilityEvent(
            eq(androidComposeView),
            argThat(
                ArgumentMatcher {
                    it.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED && it.scrollY == 2 &&
                        it.maxScrollY == 5 &&
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            it.scrollDeltaY == 2
                        } else {
                            true
                        }
                }
            )
        )
    }

    private fun createSemanticsNodeWithProperties(
        id: Int,
        mergeDescendants: Boolean,
        properties: (SemanticsPropertyReceiver.() -> Unit)
    ): SemanticsNode {
        val semanticsModifier = SemanticsModifierCore(id, mergeDescendants, false, properties)
        return SemanticsNode(
            SemanticsWrapper(InnerPlaceable(LayoutNode()), semanticsModifier),
            true
        )
    }

    private fun containsAction(
        info: AccessibilityNodeInfoCompat,
        action: AccessibilityNodeInfoCompat.AccessibilityActionCompat
    ): Boolean {
        for (a in info.actionList) {
            if (a.id == action.id && a.label == action.label) {
                return true
            }
        }
        return false
    }
}
