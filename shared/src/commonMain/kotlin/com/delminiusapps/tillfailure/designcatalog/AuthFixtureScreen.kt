package com.delminiusapps.tillfailure.designcatalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.delminiusapps.tillfailure.core.designsystem.TillFailurePrimaryButton
import com.delminiusapps.tillfailure.core.designsystem.TillFailureRadii
import com.delminiusapps.tillfailure.core.designsystem.TillFailureScreenPreviews
import com.delminiusapps.tillfailure.core.designsystem.TillFailureSizing
import com.delminiusapps.tillfailure.core.designsystem.TillFailureSpacing
import com.delminiusapps.tillfailure.core.designsystem.TillFailureTextButton
import com.delminiusapps.tillfailure.core.designsystem.TillFailureTextField
import com.delminiusapps.tillfailure.core.designsystem.TillFailureTheme
import com.delminiusapps.tillfailure.core.designsystem.TillFailureTopBar

internal fun authWelcomeTitle(model: AuthFixtureUiModel): String =
    "Welcome back, ${model.firstName}."

internal fun authProgramMessage(model: AuthFixtureUiModel): String =
    "Sign in to continue your ${model.programName} program."

internal fun authPasswordVisualTransformation(passwordVisible: Boolean): VisualTransformation =
    if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation()

internal fun authPasswordToggleLabel(passwordVisible: Boolean): String =
    if (passwordVisible) "Hide" else "Show"

internal fun authPasswordToggleDescription(passwordVisible: Boolean): String =
    if (passwordVisible) "Hide password" else "Show password"

@Composable
fun AuthFixtureScreen(
    model: AuthFixtureUiModel,
    onBackClick: () -> Unit,
    onEmailChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onPasswordVisibilityToggle: () -> Unit,
    onForgotPasswordClick: () -> Unit,
    onSignInClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .safeDrawingPadding()
            .imePadding()
            .padding(horizontal = TillFailureSpacing.md, vertical = TillFailureSpacing.xs),
        verticalArrangement = Arrangement.spacedBy(TillFailureSpacing.md),
    ) {
        TillFailureTopBar(title = "Sign in", onBackClick = onBackClick)
        Text(
            text = authWelcomeTitle(model),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = authProgramMessage(model),
            color = TillFailureTheme.colors.secondaryText,
            style = MaterialTheme.typography.bodyMedium,
        )
        TillFailureTextField(
            value = model.email,
            onValueChange = onEmailChanged,
            label = "Email",
            supportingText = model.emailSupportingText,
            isError = model.emailHasError,
        )
        TillFailureTextField(
            value = model.password,
            onValueChange = onPasswordChanged,
            label = "Password",
            visualTransformation = authPasswordVisualTransformation(model.passwordVisible),
            trailingContent = {
                Box(
                    modifier = Modifier
                        .sizeIn(
                            minWidth = TillFailureSizing.minimumTouchTarget,
                            minHeight = TillFailureSizing.minimumTouchTarget,
                        )
                        .clip(RoundedCornerShape(TillFailureRadii.sm))
                        .clickable(
                            role = Role.Button,
                            onClick = onPasswordVisibilityToggle,
                        )
                        .semantics {
                            contentDescription = authPasswordToggleDescription(model.passwordVisible)
                        }
                        .padding(horizontal = TillFailureSpacing.xs),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = authPasswordToggleLabel(model.passwordVisible),
                        color = TillFailureTheme.colors.secondaryText,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            },
        )
        TillFailureTextButton(
            text = "Forgot password?",
            onClick = onForgotPasswordClick,
            modifier = Modifier.fillMaxWidth(),
        )
        TillFailurePrimaryButton(
            text = "Sign in",
            onClick = onSignInClick,
            modifier = Modifier.fillMaxWidth(),
            loading = model.isSubmitting,
        )
        Spacer(Modifier.height(TillFailureSpacing.xl))
        Text(
            text = "Your training data is encrypted and private.",
            modifier = Modifier.padding(top = TillFailureSpacing.xxl),
            color = TillFailureTheme.colors.secondaryText,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@TillFailureScreenPreviews
@Composable
private fun AuthFixtureScreenPreview() {
    var model by remember { mutableStateOf(CatalogFixtures.auth) }
    TillFailureTheme {
        AuthFixtureScreen(
            model = model,
            onBackClick = {},
            onEmailChanged = { model = model.copy(email = it) },
            onPasswordChanged = { model = model.copy(password = it) },
            onPasswordVisibilityToggle = { model = model.copy(passwordVisible = !model.passwordVisible) },
            onForgotPasswordClick = {},
            onSignInClick = {},
        )
    }
}
