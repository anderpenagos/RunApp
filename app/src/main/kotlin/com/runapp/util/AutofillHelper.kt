package com.runapp.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.AutofillNode
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalAutofill
import androidx.compose.ui.platform.LocalAutofillTree

/**
 * Modifier que registra um campo para autofill do Android.
 *
 * Uso:
 *   OutlinedTextField(
 *       modifier = Modifier.autofill(
 *           autofillTypes = listOf(AutofillType.Password),
 *           onFill = { viewModel.onApiKeyChange(it) }
 *       )
 *   )
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.autofill(
    autofillTypes: List<AutofillType>,
    onFill: (String) -> Unit
): Modifier = composed {
    val autofill = LocalAutofill.current
    val autofillNode = remember {
        AutofillNode(autofillTypes = autofillTypes, onFill = onFill)
    }
    LocalAutofillTree.current += autofillNode

    this
        .onGloballyPositioned { coordinates ->
            autofillNode.boundingBox = coordinates.boundsInWindow()
        }
        .onFocusChanged { focusState ->
            autofill?.run {
                if (focusState.isFocused) requestAutofillForNode(autofillNode)
                else cancelAutofillForNode(autofillNode)
            }
        }
}
