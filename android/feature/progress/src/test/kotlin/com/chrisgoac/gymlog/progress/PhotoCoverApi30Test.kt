package com.chrisgoac.gymlog.progress

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.chrisgoac.gymlog.designsystem.theme.GymLogTheme
import com.chrisgoac.gymlog.designsystem.component.PhotoTile
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Before API 31 there is no blur: a concealed photo gets a neutral cover that says how to reveal it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class PhotoCoverApi30Test {
    @get:Rule val rule = createComposeRule()

    @Test fun concealedTileShowsANeutralCoverWithTheInstruction() {
        var tapped = false
        rule.setContent {
            GymLogTheme {
                PhotoTile(
                    imagePath = "/none/a.jpg", description = "Frente, 12 de agosto de 2026. Foto oculta, toca para mostrar",
                    statusText = "Frente", placeholderText = "Sin vista previa", concealed = true, concealedText = "Toca para ver",
                    onClick = { tapped = true },
                )
            }
        }
        rule.onNodeWithContentDescription("Frente, 12 de agosto de 2026. Foto oculta, toca para mostrar").performClick()
        assertTrue(tapped)
    }
}
