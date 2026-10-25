package com.delminiusapps.tillfailure.clients

import androidx.lifecycle.ViewModel
import com.delminiusapps.tillfailure.firebase.FirebaseCancellation
import com.delminiusapps.tillfailure.invitations.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

data class NoteOwner(val scope:ClientScope,val clientUid:String,val visit:Long)
enum class NotePrompt { Leave, Reload }
data class TrainerNoteState(val owner:NoteOwner?=null,val baseline:NoteRead?=null,val body:String="",val busy:Boolean=false,
    val pending:Boolean=false,val saved:Boolean=false,val storageLocked:Boolean=false,val conflict:Boolean=false,val denied:Boolean=false,val message:String="",
    val prompt:NotePrompt?=null,val promptId:Long=0,val exit:String?=null) {
    val dirty get()=baseline!=null && body!=(baseline.note?.body?:"")
    override fun toString()="TrainerNoteState(redacted)"
}
sealed interface NoteEvent {
    data class Body(val text:String):NoteEvent { override fun toString()="NoteEvent.Body(redacted)" }
    data object Save:NoteEvent
    data object Reload:NoteEvent
    data object RetryOriginal:NoteEvent
    data class Leave(val exit:String?=null):NoteEvent
    data object Confirm:NoteEvent
    data object CancelPrompt:NoteEvent
}
data class NoteNavigation(val owner:NoteOwner,val exit:String?,val sequence:Long)
/** Destination-owned draft; only the native shared ledger owns submitted work. */
class TrainerNoteViewModel(private val client:InvitationClient):ViewModel() {
    private val mutable=MutableStateFlow(TrainerNoteState());val state=mutable.asStateFlow()
    private val effects=Channel<NoteNavigation>(Channel.BUFFERED);val effect=effects.receiveAsFlow()
    private var operation:FirebaseCancellation?=null;private var observer:FirebaseCancellation?=null
    private var generation=0L;private var sequence=0L;private var navigating=false;private var consumedSequence=-1L;private var disposed=false
    fun bind(owner:NoteOwner) {
        if(disposed || mutable.value.owner==owner) return
        clear();mutable.value=TrainerNoteState(owner=owner)
        observer=client.observePending(owner.scope.uid) { if(mutable.value.owner==owner) pending() }
        pending();if(!mutable.value.pending && !mutable.value.storageLocked) load()
    }
    fun clear() {
        generation++;operation?.cancel();observer?.cancel();operation=null;observer=null;navigating=false
        while(effects.tryReceive().isSuccess) { }
        mutable.value=TrainerNoteState()
    }
    private fun pending() {
        val uid=mutable.value.owner?.scope?.uid?:return
        val p=runCatching { client.pending(uid) }
        mutable.value=mutable.value.copy(pending=p.getOrNull()!=null,storageLocked=p.isFailure)
    }
    fun onEvent(owner:NoteOwner,promptId:Long,event:NoteEvent) {
        val s=mutable.value
        if(disposed || navigating || s.owner!=owner) return
        if(event is NoteEvent.Leave) {
            if(s.prompt!=null) return
            if(s.dirty && !s.pending) mutable.value=s.copy(prompt=NotePrompt.Leave,promptId=++sequence,exit=event.exit)
            else navigate(owner,event.exit)
            return
        }
        if(event==NoteEvent.Confirm || event==NoteEvent.CancelPrompt) {
            if(s.prompt==null || s.promptId!=promptId) return
            mutable.value=s.copy(prompt=null)
            if(event==NoteEvent.Confirm) { if(s.prompt==NotePrompt.Leave) navigate(owner,s.exit) else load() }
            return
        }
        if(s.busy || s.prompt!=null) return
        if(event==NoteEvent.RetryOriginal) {
            val p=runCatching { client.pending(owner.scope.uid) }.getOrNull()?:return
            if(p.action=="save-trainer-note" && p.workspaceId==owner.scope.workspaceId && p.noteSave?.clientUid==owner.clientUid && !p.resolutionStarted)
                execute(CommandResolution.original(p))
            return
        }
        if(s.pending || s.storageLocked || s.denied) return
        when(event) {
            is NoteEvent.Body -> if(s.baseline!=null && !s.conflict && event.text.length<=8000) mutable.value=s.copy(body=event.text,message="",saved=false)
            NoteEvent.Save -> if(s.baseline!=null && s.dirty && !s.conflict) {
                val text=canonicalNoteBody(s.body);if(!validNoteBody(text)) {mutable.value=s.copy(message="Enter non-sensitive text, up to 2,000 characters (8,000 UTF-8 bytes).");return}
                val a=s.baseline.authority
                val request=runCatching { TrainerNoteProtocol.save(owner.scope.workspaceId,NoteSave(owner.clientUid,text,client.newNoteBindingNonce(),s.baseline.note?.revision?:0,
                    a.authorMembershipRevision,a.subjectMembershipRevision,a.authorLifecycleRevision,a.subjectLifecycleRevision),client.newCommandKey()) }.getOrNull()
                if(request==null || request.pending(owner.scope.uid)?.let { validPendingCommand(owner.scope.uid,it) }!=true) {mutable.value=s.copy(message="Protected Save is unavailable.");return}
                mutable.value=s.copy(body=text);execute(request)
            }
            NoteEvent.Reload -> if(s.dirty) mutable.value=s.copy(prompt=NotePrompt.Reload,promptId=++sequence) else load()
            else -> Unit
        }
    }
    private fun navigate(owner:NoteOwner,exit:String?) {
        navigating=true;generation++;operation?.cancel()
        mutable.value=mutable.value.copy(body="",baseline=null,prompt=null,busy=false)
        effects.trySend(NoteNavigation(owner,exit,++sequence))
    }
    fun consumeNavigation(n:NoteNavigation):Boolean {
        if(disposed || !navigating || mutable.value.owner!=n.owner || n.sequence!=sequence || consumedSequence==n.sequence) return false
        consumedSequence=n.sequence;return true
    }
    private fun load() { val o=mutable.value.owner?:return;execute(TrainerNoteProtocol.read(o.scope.workspaceId,o.clientUid)) }
    private fun execute(request:InvitationRequest) {
        val o=mutable.value.owner?:return;val captured=++generation
        mutable.value=mutable.value.copy(busy=true,message="Contacting the notes service…")
        operation=client.execute(o.scope.uid,request) { result ->
            if(disposed || navigating || captured!=generation || mutable.value.owner!=o) return@execute
            val s=mutable.value;val read=result.reply?.noteRead;val decision=result.reply?.noteDecision
            when {
                read!=null -> mutable.value=s.copy(busy=false,baseline=read,body=read.note?.body?:"",conflict=false,message=if(s.saved) "Note saved." else "")
                decision?.status=="note-saved" -> {mutable.value=s.copy(busy=false,baseline=null,body="",saved=true,message="Saved. Reloading current note…");pending();load();return@execute}
                decision?.status=="note-conflict" -> mutable.value=s.copy(busy=false,conflict=true,message="This note changed elsewhere. Reload current values before saving again. Your foreground draft is still here.")
                result.error=="unauthorized" || result.error=="unauthenticated" || result.error=="unverified-email" -> {
                    generation++;mutable.value=TrainerNoteState(owner=o,denied=true,message="Note access is no longer available. Submitted work remains protected in Invitations and recovery.")
                }
                else -> mutable.value=s.copy(busy=false,message=if(request.action=="save-trainer-note") "Save is unresolved. Retry the original or open Invitations and recovery; sign-out stays blocked." else "Note unavailable. Retry loading while connected.")
            }
            pending()
        }
        pending()
    }
    override fun onCleared() { clear();disposed=true;effects.close() }
}
