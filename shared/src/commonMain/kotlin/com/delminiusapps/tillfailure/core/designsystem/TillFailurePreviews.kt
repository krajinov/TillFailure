package com.delminiusapps.tillfailure.core.designsystem

import androidx.compose.ui.tooling.preview.Preview

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.BINARY)
@Preview(name = "Small phone", widthDp = 320, heightDp = 700)
@Preview(name = "Approved viewport", widthDp = 393, heightDp = 852)
@Preview(name = "Large text 200%", widthDp = 393, heightDp = 852, fontScale = 2f)
annotation class TillFailureScreenPreviews
