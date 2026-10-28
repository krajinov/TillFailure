package com.delminiusapps.tillfailure.exercises

import com.delminiusapps.tillfailure.firebase.FirebaseCancellation
import com.delminiusapps.tillfailure.invitations.*
import com.delminiusapps.tillfailure.identity.*
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class CustomExercisesViewModelTest {
    private val id="00000000-0000-4000-8000-000000000001"
    private val owner=CustomExerciseVisit(ExerciseScope("A","w",1),1,CustomExerciseMode.Editor)
    private val authority=ExerciseAuthority(1,1,1)
    @BeforeTest fun setup(){Dispatchers.setMain(UnconfinedTestDispatcher())}
    @AfterTest fun cleanup(){Dispatchers.resetMain()}
    private class Repository:CustomExerciseRepository,InvitationClient {
        override val commands get()=this;override val memory=InvitationMemory()
        val reads=mutableListOf<(CustomExerciseResult)->Unit>();val submits=mutableListOf<InvitationRequest>();val replies=mutableListOf<(InvitationOutcome)->Unit>()
        override fun newExerciseId()="00000000-0000-4000-8000-000000000001"
        override fun newCommandKey()="key"
        var original:PendingInvitationCommand?=null
        override fun pending(uid:String):PendingInvitationCommand?=original
        override fun read(scope:ExerciseScope,request:CustomExerciseReadRequest,callback:(CustomExerciseResult)->Unit):FirebaseCancellation {reads+=callback;return FirebaseCancellation{}}
        override fun execute(uid:String,request:InvitationRequest,callback:(InvitationOutcome)->Unit):FirebaseCancellation {submits+=request;replies+=callback;return FirebaseCancellation{}}
    }
    @Test fun exactSaveDuplicateSaveAndDirtyNavigationAreFenced()=runBlocking {
        val r=Repository();val vm=CustomExercisesViewModel(r);vm.bind(owner);r.reads.last()(CustomExerciseResult(authority=authority))
        vm.onEvent(owner,0,CustomExerciseEvent.Name("Synthetic"));vm.onEvent(owner,0,CustomExerciseEvent.Instructions("Original demo."));vm.onEvent(owner,0,CustomExerciseEvent.Muscle("legs"))
        vm.onEvent(owner,0,CustomExerciseEvent.Leave());val prompt=vm.state.value.promptId;vm.onEvent(owner,prompt+1,CustomExerciseEvent.Confirm);assertNotNull(vm.state.value.prompt)
        vm.onEvent(owner,prompt,CustomExerciseEvent.Stay);vm.onEvent(owner,0,CustomExerciseEvent.Save);vm.onEvent(owner,0,CustomExerciseEvent.Save)
        assertEquals(1,r.submits.size);assertEquals(authority,r.submits.single().exerciseWrite!!.authority);assertEquals(listOf("legs"),r.submits.single().exerciseWrite!!.muscleGroups)
        val result=async(start=CoroutineStart.UNDISPATCHED){vm.effect.first()}
        r.replies.last()(InvitationOutcome(reply=InvitationReply(exerciseDecision=CustomExerciseDecision("exercise-created","w",id,1,false))))
        val n=result.await();assertTrue(vm.consume(n));assertFalse(vm.consume(n));vm.onEvent(owner,0,CustomExerciseEvent.Save);assertEquals(1,r.submits.size);vm.clear()
    }
    @Test fun conflictRequiresExplicitReloadAndFailedReadPreservesForegroundDraft() {
        val r=Repository();val vm=CustomExercisesViewModel(r);val o=owner.copy(exerciseId=id);vm.bind(o)
        val saved=CustomExercise(1,1,"w",id,"A","Saved","Original",emptyList(),emptyList(),1,1000,1000)
        r.reads.last()(CustomExerciseResult(authority=authority,exercise=saved));vm.onEvent(o,0,CustomExerciseEvent.Instructions("Draft"));vm.onEvent(o,0,CustomExerciseEvent.Save)
        r.replies.last()(InvitationOutcome(reply=InvitationReply(exerciseDecision=CustomExerciseDecision("exercise-conflict","w",id,2,false))))
        assertTrue(vm.state.value.conflict);vm.onEvent(o,0,CustomExerciseEvent.Save);assertEquals(1,r.submits.size)
        vm.onEvent(o,0,CustomExerciseEvent.Reload);vm.onEvent(o,vm.state.value.promptId,CustomExerciseEvent.Confirm);r.reads.last()(CustomExerciseResult(error="unavailable"));assertEquals("Draft",vm.state.value.instructions)
        vm.onEvent(o,0,CustomExerciseEvent.Reload);vm.onEvent(o,vm.state.value.promptId,CustomExerciseEvent.Confirm);r.reads.last()(CustomExerciseResult(authority=authority,exercise=saved.copy(instructions="Current",revision=2)))
        assertEquals("Current",vm.state.value.instructions);assertFalse(vm.state.value.conflict);vm.clear()
    }
    @Test fun ownerRetirementAndReceivedEffectsCannotReviveDraft()=runBlocking {
        val r=Repository();val vm=CustomExercisesViewModel(r);vm.bind(owner);r.reads.last()(CustomExerciseResult(authority=authority));vm.onEvent(owner,0,CustomExerciseEvent.Name("Draft"))
        vm.onEvent(owner,0,CustomExerciseEvent.Leave());val result=async(start=CoroutineStart.UNDISPATCHED){vm.effect.first()};vm.onEvent(owner,vm.state.value.promptId,CustomExerciseEvent.Confirm);val n=result.await()
        vm.clear();assertFalse(vm.consume(n));vm.bind(owner.copy(visit=2));r.reads.first()(CustomExerciseResult(authority=authority));assertEquals("",vm.state.value.name)
        r.reads.last()(CustomExerciseResult(error="unauthorized"));assertTrue(vm.state.value.denied);assertNull(vm.state.value.authority);vm.clear()
    }
    @Test fun customBackRefreshesItsSourceVisitAndRejectsStaleBack() {
        val scope=ShellOwner("A","w","A","trainer",1)
        val source=ShellDestination(ShellPage.CustomExercises,owner=scope,refreshVersion=2)
        val stack=mutableListOf<NavKey>(ShellDestination(ShellPage.Exercises,owner=scope,refreshVersion=1),source)
        backFromCustomExercises(stack,source);assertEquals(3,(stack.single() as ShellDestination).refreshVersion)
        backFromCustomExercises(stack,source);assertEquals(1,stack.size)
    }
    @Test fun unrelatedOrResolvingPendingWorkOffersOnlyGenericRecovery() {
        val r=Repository();r.original=PendingInvitationCommand("A","create","old",workspaceId="w",email="synthetic@example.invalid")
        val vm=CustomExercisesViewModel(r);vm.bind(owner);assertTrue(vm.state.value.pending);assertFalse(vm.state.value.retryOriginal)
        vm.onEvent(owner,0,CustomExerciseEvent.RetryOriginal);assertTrue(r.submits.isEmpty());vm.clear()
        r.original=CustomExerciseProtocol.write("w",CustomExerciseWrite(id,0,authority,"Synthetic","Original",emptyList(),emptyList()),"key").pending("A")!!.copy(resolutionStarted=true)
        vm.bind(owner.copy(visit=2));assertFalse(vm.state.value.retryOriginal);vm.clear()
    }
    @Test fun settledConflictIsNotReplacedByAnExpiredSaveTimeout()=runTest {
        val r=Repository();val vm=CustomExercisesViewModel(r);vm.bind(owner);r.reads.last()(CustomExerciseResult(authority=authority))
        vm.onEvent(owner,0,CustomExerciseEvent.Name("Synthetic"));vm.onEvent(owner,0,CustomExerciseEvent.Instructions("Original"));vm.onEvent(owner,0,CustomExerciseEvent.Save)
        r.replies.last()(InvitationOutcome(reply=InvitationReply(exerciseDecision=CustomExerciseDecision("exercise-conflict","w",id,1,false))))
        val settled=vm.state.value;assertTrue(settled.conflict);assertFalse(settled.busy)
        advanceTimeBy(21_000);runCurrent();assertEquals(settled,vm.state.value);assertEquals(1,r.submits.size);vm.clear()
    }
    @Test fun queuedSaveAfterConfirmedBackCannotSubmitOrReviveTheDraft()=runBlocking {
        val r=Repository();val vm=CustomExercisesViewModel(r);vm.bind(owner);r.reads.last()(CustomExerciseResult(authority=authority))
        vm.onEvent(owner,0,CustomExerciseEvent.Name("Synthetic"));vm.onEvent(owner,0,CustomExerciseEvent.Instructions("Original"));vm.onEvent(owner,0,CustomExerciseEvent.Leave())
        val prompt=vm.state.value.promptId;val result=async(start=CoroutineStart.UNDISPATCHED){vm.effect.first()};vm.onEvent(owner,prompt,CustomExerciseEvent.Confirm)
        vm.onEvent(owner,prompt,CustomExerciseEvent.Save);r.reads.last()(CustomExerciseResult(authority=authority))
        assertTrue(r.submits.isEmpty());assertEquals("",vm.state.value.name);assertNull(vm.state.value.authority);assertTrue(vm.consume(result.await()));vm.clear()
    }
    @Test fun invalidTextAndTaxonomyNeverSubmitOrTruncate() {
        val r=Repository();val vm=CustomExercisesViewModel(r);vm.bind(owner);r.reads.last()(CustomExerciseResult(authority=authority))
        vm.onEvent(owner,0,CustomExerciseEvent.Name(" Name "));vm.onEvent(owner,0,CustomExerciseEvent.Instructions("x".repeat(2001)));vm.onEvent(owner,0,CustomExerciseEvent.Save)
        assertTrue(r.submits.isEmpty());assertEquals(2001,vm.state.value.instructions.length);assertTrue(vm.state.value.message.isNotEmpty())
        vm.onEvent(owner,0,CustomExerciseEvent.Equipment("bodyweight"));vm.onEvent(owner,0,CustomExerciseEvent.Equipment("barbell"));assertEquals(listOf("bodyweight"),vm.state.value.equipment);vm.clear()
    }
}
