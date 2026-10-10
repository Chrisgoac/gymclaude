package com.chrisgoac.gymlog

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import com.chrisgoac.gymlog.designsystem.component.HierroTextField
import com.chrisgoac.gymlog.designsystem.theme.GymLogTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h800dp-xhdpi")
class HierroTextFieldTest {
    @get:Rule val rule = createComposeRule()

    @Test fun editableTextIsTypedValueAndLabelIsDiscoverable(): Unit = with(rule) {
        setContent {
            var value by remember { mutableStateOf("") }
            GymLogTheme { HierroTextField(value, { value = it }, label = "Peso") }
        }
        onNode(hasSetTextAction()).performTextInput("82.5")
        val config = onNode(hasSetTextAction()).fetchSemanticsNode().config
        assertEquals("82.5", config.getOrNull(SemanticsProperties.EditableText)?.text)
        // The label must not replace the field's content description (TalkBack would read it instead of the value).
        assertNull(config.getOrNull(SemanticsProperties.ContentDescription))
        onNodeWithText("PESO").assertExists()
    }
}
