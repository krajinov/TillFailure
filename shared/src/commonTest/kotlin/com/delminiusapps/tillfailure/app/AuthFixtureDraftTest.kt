package com.delminiusapps.tillfailure.app

import com.delminiusapps.tillfailure.designcatalog.CatalogFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthFixtureDraftTest {
    @Test
    fun emailAndPasswordEditsFlowIntoTheRenderedModelWithoutChangingTheFixture() {
        val fixture = CatalogFixtures.auth
        var draft = AuthFixtureDraft.from(fixture)

        draft = draft.withEmail("edited@example.com")
        draft = draft.withPassword("passXYZword")
        assertEquals("edited@example.com", draft.toUiModel(fixture).email)
        assertEquals("passXYZword", draft.toUiModel(fixture).password)

        // Insertion and deletion from the middle must keep the exact value the text field reports.
        draft = draft.withEmail("edited+new@example.com")
        draft = draft.withPassword("password")
        assertEquals("edited+new@example.com", draft.toUiModel(fixture).email)
        assertEquals("password", draft.toUiModel(fixture).password)
        assertEquals("alex.morgan@example.com", fixture.email)
        assertEquals("strongpassword", fixture.password)
    }

    @Test
    fun visibilityTogglesPreserveBothInputsAndANewEntryStartsFromFixtureDefaults() {
        val fixture = CatalogFixtures.auth
        var draft = AuthFixtureDraft.from(fixture)
            .withEmail("new@example.com")
            .withPassword("new password")

        draft = draft.togglePasswordVisibility()
        assertTrue(draft.toUiModel(fixture).passwordVisible)
        assertEquals("new password", draft.toUiModel(fixture).password)
        assertEquals("new@example.com", draft.toUiModel(fixture).email)

        draft = draft.togglePasswordVisibility()
        assertFalse(draft.toUiModel(fixture).passwordVisible)
        assertEquals("new password", draft.toUiModel(fixture).password)

        val reopened = AuthFixtureDraft.from(fixture).toUiModel(fixture)
        assertEquals(fixture.email, reopened.email)
        assertEquals(fixture.password, reopened.password)
        assertFalse(reopened.passwordVisible)
    }
}
