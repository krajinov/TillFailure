package com.delminiusapps.tillfailure.programs

import com.delminiusapps.tillfailure.exercises.*
import com.delminiusapps.tillfailure.firebase.FirebaseCancellation
import com.delminiusapps.tillfailure.invitations.*
import com.delminiusapps.tillfailure.identity.*
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class DraftsViewModelTest {
    private val owner=DraftVisit(ExerciseScope("A","w",1),1)
    private val authority=ExerciseAuthority(1,1,1)
    @BeforeTest fun setup(){Dispatchers.setMain(UnconfinedTestDispatcher())}
    @AfterTest fun cleanup(){Dispatchers.resetMain()}
    private class Repository:DraftRepository,InvitationClient {
        override val commands get()=this
        override val memory=InvitationMemory()
        val reads=mutableListOf<Pair<DraftReadRequest,(DraftReadResult)->Unit>>()
        val submits=mutableListOf<InvitationRequest>();val replies=mutableListOf<(InvitationOutcome)->Unit>()
        var holdSystem=false
        var systemReply:((ExerciseResult)->Unit)?=null
        private var counter=0
        override fun newId()="00000000-0000-4000-8000-${(++counter).toString().padStart(12,'0')}"
        override fun newCommandKey()=resolutionCommandKey("command${counter++}")
        override fun pending(uid:String):PendingInvitationCommand?=null
        override fun draftAdmission(uid:String,request:InvitationRequest)=true
        override fun execute(uid:String,request:InvitationRequest,callback:(InvitationOutcome)->Unit):FirebaseCancellation {submits+=request;replies+=callback;return FirebaseCancellation{}}
        override fun read(scope:ExerciseScope,request:DraftReadRequest,callback:(DraftReadResult)->Unit):FirebaseCancellation {reads+=request to callback;return FirebaseCancellation{}}
        override val systemExercises=object:SystemExerciseRepository {override fun read(uid:String,afterId:String?,exerciseId:String?,callback:(ExerciseResult)->Unit):FirebaseCancellation {if(holdSystem)systemReply=callback else callback(ExerciseResult());return FirebaseCancellation{}}}
        override val customExercises=object:CustomExerciseRepository {
            override val commands get()=this@Repository
            override fun newExerciseId()=newId()
            override fun read(scope:ExerciseScope,request:CustomExerciseReadRequest,callback:(CustomExerciseResult)->Unit):FirebaseCancellation {callback(CustomExerciseResult(authority=ExerciseAuthority(1,1,1)));return FirebaseCancellation{}}
        }
    }
    private fun create(r:Repository,vm:DraftsViewModel) {
        vm.bind(owner);r.reads.last().second(DraftReadResult(authority=authority))
        vm.onEvent(owner,0,DraftEvent.Create);r.reads.last().second(DraftReadResult(authority=authority))
    }
    private inner class Shell(private val vm:DraftsViewModel) {
        val shellOwner=ShellOwner("A","w","A","trainer",1)
        val destination=ShellDestination(ShellPage.Programs,owner=shellOwner,refreshVersion=owner.visit)
        val stack=mutableListOf<NavKey>(ShellDestination(ShellPage.Home,owner=shellOwner),destination)
        var departures=0
        fun select(page:ShellPage=ShellPage.Programs) = requestShellPage(stack,shellOwner,page,destination,
            destination to { target -> departures++;vm.onEvent(owner,vm.state.value.promptId,DraftEvent.Leave(target)) },
            null,null,{error("Unexpected profile departure")})
    }
    @Test fun activeProgramsReselectionKeepsListAndCleanEditorUsable() {
        val r=Repository();val vm=DraftsViewModel(r);val shell=Shell(vm)
        vm.bind(owner);r.reads.last().second(DraftReadResult(authority=authority))
        repeat(3){shell.select()};vm.onEvent(owner,0,DraftEvent.Create)
        assertEquals("draft-prepare",r.reads.last().first.action)
        r.reads.last().second(DraftReadResult(authority=authority))
        repeat(3){shell.select()};vm.onEvent(owner,0,DraftEvent.Title("Still editable"))
        assertEquals("Still editable",vm.state.value.draft!!.title)
        assertNull(vm.state.value.prompt);assertEquals(0,shell.departures)
        vm.onEvent(owner,0,DraftEvent.Save);assertEquals(1,r.submits.size);vm.clear()
    }
    @Test fun activeProgramsReselectionPreservesDirtyEditorAndPickerThenSaveAndBack() {
        val r=Repository();val vm=DraftsViewModel(r);create(r,vm);val shell=Shell(vm)
        vm.onEvent(owner,0,DraftEvent.Title("Keep edits"));vm.onEvent(owner,0,DraftEvent.AddWorkout)
        val draft=vm.state.value.draft!!
        repeat(3){shell.select()};assertEquals(draft,vm.state.value.draft);assertNull(vm.state.value.prompt)
        vm.onEvent(owner,0,DraftEvent.Picker(draft.workouts.single().id,"system"))
        val picker=vm.state.value;repeat(3){shell.select()};assertEquals(picker,vm.state.value)
        vm.onEvent(owner,0,DraftEvent.Leave());assertNull(vm.state.value.pickerWorkout)
        assertEquals(draft,vm.state.value.draft);assertNull(vm.state.value.prompt)
        vm.onEvent(owner,0,DraftEvent.Title("After picker"));vm.onEvent(owner,0,DraftEvent.Save)
        val submitted=r.submits.single().draftSave!!;assertEquals("After picker",submitted.title)
        r.replies.single()(InvitationOutcome(reply=InvitationReply(draftDecision=DraftDecision("draft-saved","w",draft.templateId,1,false))))
        r.reads.last().second(DraftReadResult(authority=authority,draft=SavedDraft(1,1,"w",draft.templateId,"A",1,1000,1000,submitted.title,submitted.description,submitted.workouts)))
        repeat(3){shell.select()};vm.onEvent(owner,0,DraftEvent.Leave())
        assertEquals("draft-list",r.reads.last().first.action);assertNull(vm.state.value.draft)
        assertEquals(0,shell.departures);vm.clear()
    }
    @Test fun differentTabConfirmsDepartureAndFencesQueuedSaveAtShellBoundary()=runTest {
        val r=Repository();val vm=DraftsViewModel(r);create(r,vm);val shell=Shell(vm)
        val navigations=mutableListOf<DraftNavigation>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)){vm.effect.collect{navigations+=it}}
        vm.onEvent(owner,0,DraftEvent.Title("Unsent"));shell.select(ShellPage.Home)
        assertEquals("leave",vm.state.value.prompt);assertEquals("Home",vm.state.value.exit);assertTrue(navigations.isEmpty())
        vm.onEvent(owner,vm.state.value.promptId,DraftEvent.Stay)
        vm.onEvent(owner,0,DraftEvent.Title("Still here"));assertEquals("Still here",vm.state.value.draft!!.title)
        shell.select(ShellPage.Home);val prompt=vm.state.value.promptId
        vm.onEvent(owner,prompt-1,DraftEvent.Confirm);assertNotNull(vm.state.value.prompt)
        vm.onEvent(owner,prompt,DraftEvent.Confirm)
        vm.onEvent(owner,prompt,DraftEvent.Save);vm.onEvent(owner,prompt,DraftEvent.Title("Stale"))
        assertTrue(r.submits.isEmpty());assertEquals("Still here",vm.state.value.draft!!.title)
        val navigation=navigations.single();assertEquals("Home",navigation.page);assertTrue(vm.consume(navigation))
        openShellPage(shell.stack,shell.shellOwner,ShellPage.Home,shell.destination)
        assertEquals(ShellPage.Home,(shell.stack.single() as ShellDestination).page)
        assertFalse(vm.consume(navigation));vm.clear()
    }
    @Test fun shellIgnoresSamePageAndStaleSelectionsBeforeAllDepartureHandlers() {
        val o=ShellOwner("A","w","A","trainer",1)
        for(page in listOf(ShellPage.Programs,ShellPage.CustomExercises,ShellPage.CustomExerciseDetails,ShellPage.CustomExerciseEditor,ShellPage.TrainerNote,ShellPage.EditProfile)) {
            val source=ShellDestination(page,owner=o);val stack=mutableListOf<NavKey>(source);var departures=0
            val leave:Pair<ShellDestination,(String?)->Unit> = source to {departures++}
            fun select(target:ShellPage,from:ShellDestination=source,by:ShellOwner=o)=
                requestShellPage(stack,by,target,from,leave,leave,leave,{departures++})
            repeat(3){select(page)};assertEquals(0,departures,"same page: $page")
            select(ShellPage.Home,source.copy(refreshVersion=1));select(ShellPage.Home,by=o.copy(epoch=2))
            assertEquals(0,departures,"stale owner/destination: $page")
            select(ShellPage.Home);assertEquals(1,departures,"real departure: $page")
            assertEquals(source,stack.single())
        }
    }
    @Test fun explicitSaveCapturesIncompleteDraftAndDuplicateTapIsFenced() {
        val r=Repository();val vm=DraftsViewModel(r);create(r,vm)
        vm.onEvent(owner,0,DraftEvent.AddWorkout);vm.onEvent(owner,0,DraftEvent.Save);vm.onEvent(owner,0,DraftEvent.Save)
        assertEquals(1,r.submits.size);assertTrue(r.submits.single().draftSave!!.valid());assertEquals("",r.submits.single().draftSave!!.workouts.single().title)
        val id=r.submits.single().draftSave!!.templateId
        r.replies.single()(InvitationOutcome(reply=InvitationReply(draftDecision=DraftDecision("draft-saved","w",id,1,false))))
        assertEquals("draft-get",r.reads.last().first.action);assertTrue(vm.state.value.busy);vm.clear()
    }
    @Test fun confirmedDepartureRejectsQueuedSaveAndOldCallbacks() {
        val r=Repository();val vm=DraftsViewModel(r);create(r,vm);vm.onEvent(owner,0,DraftEvent.Title("Unsent"))
        vm.onEvent(owner,0,DraftEvent.Leave());val prompt=vm.state.value.promptId
        vm.onEvent(owner,prompt+1,DraftEvent.Confirm);assertNotNull(vm.state.value.prompt)
        vm.onEvent(owner,prompt,DraftEvent.Confirm);vm.onEvent(owner,0,DraftEvent.Save);assertTrue(r.submits.isEmpty())
        val stale=r.reads.first().second;vm.clear();stale(DraftReadResult(authority=authority));assertNull(vm.state.value.owner)
    }
    @Test fun conflictRequiresExplicitReloadAndFailurePreservesForegroundEdits() {
        val r=Repository();val vm=DraftsViewModel(r);create(r,vm);vm.onEvent(owner,0,DraftEvent.Title("Unsent"));vm.onEvent(owner,0,DraftEvent.Save)
        val id=r.submits.single().draftSave!!.templateId
        r.replies.single()(InvitationOutcome(reply=InvitationReply(draftDecision=DraftDecision("draft-conflict","w",id,1,false))))
        vm.onEvent(owner,0,DraftEvent.Title("Must stay blocked"));assertEquals("Unsent",vm.state.value.draft!!.title)
        vm.onEvent(owner,0,DraftEvent.Save);assertEquals(1,r.submits.size)
        vm.onEvent(owner,0,DraftEvent.Reload);vm.onEvent(owner,vm.state.value.promptId,DraftEvent.Confirm)
        r.reads.last().second(DraftReadResult(error="unavailable"));assertEquals("Unsent",vm.state.value.draft!!.title);assertEquals("draft-conflict",vm.state.value.conflict)
        vm.clear()
    }
    @Test fun moveOperationsPreserveStableItemIdentityAndRawNumericInput() {
        val r=Repository();val vm=DraftsViewModel(r);vm.bind(owner)
        val tid=r.newId();val w1=r.newId();val w2=r.newId();val itemId=r.newId()
        val item=DraftItem(itemId,"system","synthetic",null,"a".repeat(64),"Synthetic",DraftPrescription(null,null,null,null))
        r.reads.last().second(DraftReadResult(authority=authority,items=listOf(DraftRow(tid,"Template",1))))
        vm.onEvent(owner,0,DraftEvent.Open(tid));r.reads.last().second(DraftReadResult(authority=authority,draft=SavedDraft(1,1,"w",tid,"A",1,1000,1000,"Template","",listOf(DraftWorkout(w1,"First",listOf(item)),DraftWorkout(w2,"Second",emptyList())))))
        vm.onEvent(owner,0,DraftEvent.Numbers(itemId,"kg","1."))
        vm.onEvent(owner,0,DraftEvent.MoveItem(itemId,w2))
        assertEquals(item,vm.state.value.draft!!.workouts[1].items.single());assertEquals("1.",vm.state.value.numbers[itemId]!!.kg)
        vm.onEvent(owner,0,DraftEvent.Save);assertTrue(r.submits.isEmpty());assertEquals("1.",vm.state.value.numbers[itemId]!!.kg)
        vm.onEvent(owner,0,DraftEvent.Numbers(itemId,"kg","1.234"));vm.onEvent(owner,0,DraftEvent.Save)
        assertEquals(1234,r.submits.single().draftSave!!.workouts[1].items.single().prescription.loadGrams)
        assertEquals(itemId,r.submits.single().draftSave!!.workouts[1].items.single().id);vm.clear()
    }
    @Test fun unknownSaveCannotBecomeASecondSubmissionAfterPendingClears()=runTest {
        val r=Repository();val vm=DraftsViewModel(r);create(r,vm);vm.onEvent(owner,0,DraftEvent.Save)
        advanceTimeBy(20001);runCurrent();assertTrue(vm.state.value.needsRecovery)
        vm.onEvent(owner,0,DraftEvent.Save);assertEquals(1,r.submits.size)
        val id=r.submits.single().draftSave!!.templateId
        r.replies.single()(InvitationOutcome(reply=InvitationReply(draftDecision=DraftDecision("draft-saved","w",id,1,false))))
        assertTrue(vm.state.value.needsRecovery);assertFalse(vm.state.value.busy)
        vm.onEvent(owner,0,DraftEvent.Reload);r.reads.last().second(DraftReadResult(error="unavailable"))
        vm.onEvent(owner,0,DraftEvent.Save);assertEquals(1,r.submits.size);vm.clear()
    }
    @Test fun workoutRemovalClosesTheOwnedPromptAndReturnsFocus() {
        val r=Repository();val vm=DraftsViewModel(r);create(r,vm);vm.onEvent(owner,0,DraftEvent.AddWorkout)
        val id=vm.state.value.draft!!.workouts.single().id;vm.onEvent(owner,0,DraftEvent.RemoveWorkout(id))
        vm.onEvent(owner,vm.state.value.promptId,DraftEvent.Confirm)
        assertNull(vm.state.value.prompt);assertTrue(vm.state.value.draft!!.workouts.isEmpty());assertEquals("draft-heading",vm.state.value.focusTarget)
        vm.clear()
    }
    @Test fun pickerBackInvalidatesReadAndTimerButPreservesEditor()=runTest {
        val r=Repository();val vm=DraftsViewModel(r);create(r,vm)
        vm.onEvent(owner,0,DraftEvent.Title("Foreground"));vm.onEvent(owner,0,DraftEvent.AddWorkout)
        val draft=vm.state.value.draft!!;r.holdSystem=true
        vm.onEvent(owner,0,DraftEvent.Picker(draft.workouts.single().id,"system"));assertTrue(vm.state.value.busy)
        val callback=r.systemReply!!;vm.onEvent(owner,0,DraftEvent.Leave())
        val exited=vm.state.value;assertFalse(exited.busy);assertNull(exited.pickerWorkout);assertEquals(draft,exited.draft)
        callback(ExerciseResult());advanceTimeBy(20001);runCurrent()
        assertEquals(exited,vm.state.value);vm.clear()
    }
    @Test fun completedPreparationCannotOverwriteLaterForegroundEdits() {
        val r=Repository();val vm=DraftsViewModel(r);create(r,vm)
        val callback=r.reads.last().second
        vm.onEvent(owner,0,DraftEvent.Title("Keep this edit"));val edited=vm.state.value
        callback(DraftReadResult(authority=authority))
        assertEquals(edited,vm.state.value);vm.clear()
    }

}
