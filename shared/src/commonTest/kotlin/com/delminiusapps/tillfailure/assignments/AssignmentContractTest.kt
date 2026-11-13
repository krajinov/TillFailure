package com.delminiusapps.tillfailure.assignments

import com.delminiusapps.tillfailure.invitations.*
import com.delminiusapps.tillfailure.persistence.*
import kotlinx.serialization.json.*
import kotlin.test.*

class AssignmentContractTest {
    private fun command()=AssignmentDescriptor("assign-program-version","w",resolutionCommandKey("assignment-vector"),AssignmentCreate(
        "00000000-0000-4000-8000-000000000003","00000000-0000-4000-8000-000000000004","B","00000000-0000-4000-8000-000000000001",
        "v0000000001",1,"4f9654a02f80d757330183becea5820127d1f346d1ca03f8d2f8424f652afcbe",1,"7a829022c9cb65899501c5e2da3f1286e97d65bcccd26395636290734cf84b78",
        AssignmentAuthority(1,1,1,1,1,1),1800000000000,1800000000001,1800086400001))
    @Test fun frozenVectorAndStrictDescriptors() {
        val d=command();assertTrue(d.valid("A"))
        assertEquals("3943402b94dbed13b0fe51354b9b81fb7f6ba11433b421189b46cec565fcd705",AssignmentContract.id("A",d))
        assertEquals("5252e6120cfb19f03f30c955c5e510bdec5e9f30f51db126ef8f11b93d0d5949",AssignmentContract.hash("A",d))
        val text=AssignmentContract.json.encodeToString(AssignmentDescriptor.serializer(),d)
        assertEquals(d,AssignmentContract.parseDescriptor("A",text))
        assertFails{AssignmentContract.parseDescriptor("A",text.dropLast(1)+",\"private\":true}")}
        val value=AssignmentContract.json.parseToJsonElement(text).jsonObject
        assertFails { AssignmentContract.parseDescriptor("A",JsonObject(value+("idempotencyKey" to JsonArray(listOf(JsonPrimitive(d.idempotencyKey))))).toString()) }
        assertFalse(d.valid("B"));assertFalse(d.copy(assignment=d.assignment.copy(clientUid="../other")).valid("A"))
        assertTrue(d.copy(assignment=d.assignment.copy(clientUid="name.with.dots@example.invalid")).valid("A"))
    }
    @Test fun exactMillisecondsAndArithmetic() {
        val a=command().assignment
        for(delta in listOf(0L,1L,86400000L,86400001L))assertEquals(delta in 1..86400000,a.copy(scheduledInstantMillis=a.preparedAtMillis+delta,accessExpiresAtMillis=a.preparedAtMillis+delta+86400000).valid())
        assertEquals("current",AssignmentContract.executionTime(a,a.preparedAtMillis))
        assertEquals("assignment-schedule-conflict",AssignmentContract.executionTime(a,a.scheduledInstantMillis))
        assertFails{AssignmentContract.executionTime(a,a.preparedAtMillis-1)}
        assertFalse(a.copy(accessExpiresAtMillis=a.accessExpiresAtMillis+1).valid())
        assertFalse(a.copy(preparedAtMillis=Long.MAX_VALUE).valid())
    }
    @Test fun prospectiveStatesAreIsolatedAndOversizeRefusesWithoutMutation() {
        val d=command();val base=AccountPersistenceEnvelope(uid="A",preferredWorkspaceId="w")
        assertTrue(AssignmentProspective.admission(base,d))
        assertEquals(38,AssignmentProspective.states(base,d).size)
        val original=DraftJson(base.copy(preferredWorkspaceId="x".repeat(36500)))
        assertFalse(AssignmentProspective.admission(base.copy(preferredWorkspaceId="x".repeat(36500)),d))
        assertEquals(original,DraftJson(base.copy(preferredWorkspaceId="x".repeat(36500))))
        // No new dispatcher/ledger adapter is installed by this prerequisite.
        assertFalse(validPendingCommand("A",PendingInvitationCommand("A",d.action,d.idempotencyKey,workspaceId="w")))
        assertFails{PersistenceJson.format.decodeFromString(AccountPersistenceEnvelope.serializer(),AssignmentProspective.states(base,d).first().toString())}
        for(status in AssignmentContract.statuses)assertTrue(AssignmentContract.validDecision(d,AssignmentContract.decision(d,status)))
    }
    private fun DraftJson(record:AccountPersistenceEnvelope)=PersistenceJson.format.encodeToString(AccountPersistenceEnvelope.serializer(),record)
}
