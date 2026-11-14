import { randomUUID } from 'node:crypto';
import { FieldPath, Firestore, Timestamp, Transaction } from 'firebase-admin/firestore';
import { verifiedEmulatorIdentity } from './invitations.js';
import { requireProvisioningEmulators } from './provisioning.js';
import { participantUid, strictPathIdentifier } from './input-validation.js';
import { exerciseExact as exact, exerciseBytes as bytes, exerciseId, exerciseRevision } from './custom-exercise-contract.js';
import { stableHash } from './hashing.js';
import { requireAssignmentCoverage } from './assignment-coverage.js';
import { assignmentDescriptor, assignmentBinding, assignmentCopy, assignmentCopyHash, assignmentDecision,
  validAssignmentDecision, materializeAssignment, executionTime, validateClientAssignmentDocuments,
  ASSIGNMENT_AUTHORITY, ASSIGNMENT_KIND, DAY_MS, millis } from './assignment-feasibility-contract.js';
import { validVersionId } from './program-publication-contract.js';

type Data = Record<string, any>;
const fail = (reason = 'assignment-unavailable'): never => { throw new Error(reason); };
const stamp = (v: unknown): v is Timestamp => v instanceof Timestamp && millis(v.toMillis()) && v.nanoseconds % 1000000 === 0;

/** Nine transactionally checked authority records. Auth eligibility is independently fresh. */
export async function assignmentAuthority(tx: Transaction, db: Firestore, uid: string, client: string, wid: string, trainerEmail: string, clientEmail: string): Promise<Data> {
  const docs = await tx.getAll(...[`users/${uid}`, `workspaces/${wid}`, `workspaces/${wid}/memberships/${uid}`, `workspaces/${wid}/trainerProfiles/${uid}`, `users/${uid}/membershipRefs/${wid}`,
    `users/${client}`, `workspaces/${wid}/memberships/${client}`, `workspaces/${wid}/clientProfiles/${client}`, `users/${client}/membershipRefs/${wid}`].map(p => db.doc(p)));
  if (docs.some(s => bytes(s.data() ?? null) > 16384)) fail();
  const [a, w, m, t, dir, c, cm, cp, cd] = docs.map(s => s.data());
  if (uid === client || [a,w,m,t,dir,c,cm,cp,cd].some(x => x?.schemaVersion !== 1) ||
    a?.accountStatus !== 'active' || c?.accountStatus !== 'active' || a?.emailNormalized !== trainerEmail || c?.emailNormalized !== clientEmail ||
    w?.status !== 'active' || t?.workspaceId !== wid || t?.userId !== uid || [dir,cd].some(x => x?.status !== 'active') ||
    [[m,uid,'trainer'],[cm,client,'client']].some(([x,id,role]: any[]) => x.workspaceId !== wid || x.userId !== id || x.role !== role || x.status !== 'active' || x.catalogContributionActive !== true || !exerciseRevision(x.revision)) ||
    !exact(cp, ['schemaVersion','workspaceId','userId','trainerId','createdAt','onboardingStatus','onboardingRevision','onboardingPolicyVersion','completedAt']) ||
    cp.workspaceId !== wid || cp.userId !== client || cp.trainerId !== uid || cp.onboardingStatus !== 'complete' || cp.onboardingPolicyVersion !== 1 ||
    !stamp(cp.createdAt) || !stamp(cp.completedAt) || cp.completedAt.toMillis() < cp.createdAt.toMillis()) fail('unauthorized');
  const authority = { trainerLifecycleRevision: a!.lifecycleRevision, trainerMembershipRevision: m!.revision, clientLifecycleRevision: c!.lifecycleRevision,
    clientMembershipRevision: cm!.revision, workspaceMembershipRevision: w!.membershipRevision, clientOnboardingRevision: cp!.onboardingRevision };
  if (!Object.values(authority).every(exerciseRevision) || authority.workspaceMembershipRevision >= 2147483646) fail('unauthorized');
  return authority;
}

/** Convert only the fixed contract timestamps, never arbitrary server data. */
export function assignmentStoredDocuments(docs: Map<string, Data>): Map<string, Data> {
  return new Map([...docs].map(([path, value]) => {
    const data = { ...value };
    for (const key of ['scheduledInstant','accessExpiresAt']) if (`${key}Millis` in data) {
      data[key] = Timestamp.fromMillis(data[`${key}Millis`]); delete data[`${key}Millis`];
    }
    return [path, data];
  }));
}
export function assignmentDocumentDto(v: Data): Data {
  const d = { ...v };
  for (const k of ['scheduledInstant','accessExpiresAt']) if (k in d) {
    if (!stamp(d[k])) fail(); d[`${k}Millis`] = d[k].toMillis(); delete d[k];
  }
  return d;
}
function sourceVersion(v: Data | undefined, uid: string, wid: string, tid: string, vid: string): Data {
  if (!v || !stamp(v.publishedAt)) fail('assignment-source-unavailable');
  const dto: Data = { ...v!, publishedAtMillis: v!.publishedAt.toMillis() }; delete (dto as Data).publishedAt;
  if (dto.versionId !== vid) fail('assignment-source-unavailable');
  assignmentCopy(dto, uid, wid, tid); return dto;
}
function header(v: Data | undefined, client: string, wid: string, aid: string): Data {
  if (!v || bytes(v) > 4096) fail();
  const h = assignmentDocumentDto(v!);
  if (!exact(h, ['schemaVersion','workspaceId','clientId','assignmentId','snapshotId','sourceTemplateId','sourceVersionId','sourceVersionNumber','sourceContentHash',
    'trainerId','snapshotHash','revision','lifecycleState','accessStatus','scheduledInstantMillis','accessExpiresAtMillis','timeZoneId']) ||
    h.schemaVersion !== 1 || h.workspaceId !== wid || h.clientId !== client || h.assignmentId !== aid || h.snapshotId !== 'content' ||
    h.revision !== 1 || h.lifecycleState !== 'ready' || h.accessStatus !== 'active' || h.timeZoneId !== 'UTC' || !exerciseId(h.sourceTemplateId) ||
    !Number.isInteger(h.sourceVersionNumber) || h.sourceVersionNumber < 1 || h.sourceVersionNumber > 2147483645 || h.sourceVersionId !== `v${String(h.sourceVersionNumber).padStart(10,'0')}` ||
    !/^[a-f0-9]{64}$/.test(h.snapshotHash) || !/^[a-f0-9]{64}$/.test(h.sourceContentHash) || !millis(h.scheduledInstantMillis) ||
    !millis(h.accessExpiresAtMillis) || h.accessExpiresAtMillis - h.scheduledInstantMillis !== DAY_MS) fail();
  participantUid(h.trainerId, 'trainer'); return h;
}
async function admission(db: Firestore, uid: string, clock: () => number) {
  await db.runTransaction(async tx => {
    const ref = db.doc(`programAssignmentRequests/${stableHash(uid)}`), s = await tx.get(ref), minute = Math.floor(clock()/60000);
    if (s.exists && (!exact(s.data(), ['minute','count']) || !Number.isSafeInteger(s.get('minute')) || s.get('minute') < 0 || !Number.isSafeInteger(s.get('count')) || s.get('count') < 0 || s.get('count') > 120)) fail('rate-limited');
    const count = s.get('minute') === minute ? s.get('count') : 0;
    if (count >= 120) fail('rate-limited'); tx.set(ref, { minute, count: count + 1 });
  }, { maxAttempts: 3 });
}

export async function programAssignmentOperation(db: Firestore, caller: string | undefined, raw: unknown,
  clock: () => number = Date.now, beforeWrites?: () => Promise<void>) {
  requireProvisioningEmulators(); if (!caller) fail('unauthorized');
  const uid = participantUid(caller, 'uid'), user = await verifiedEmulatorIdentity(uid), d = raw as Data;
  if(d?.action==='client-assignment-time') {
    if(!exact(d,['action','workspaceId','clientUid','assignmentId']) || bytes({data:d})>32768 || d.clientUid!==uid || !exerciseId(d.assignmentId))fail('unauthorized');
    const wid=participantUid(strictPathIdentifier(d.workspaceId,'workspace'),'workspace');await admission(db,uid,clock);const ownStarted=clock();
    return db.runTransaction(async tx=>{
      const [a,w,m,h]=await tx.getAll(...[`users/${uid}`,`workspaces/${wid}`,`workspaces/${wid}/memberships/${uid}`,`users/${uid}/workspaces/${wid}/assignedPrograms/${d.assignmentId}`].map(p=>db.doc(p)));
      if([a,w,m].some(s=>bytes(s!.data()??null)>16384) || a!.get('schemaVersion')!==1 || a!.get('accountStatus')!=='active' || a!.get('emailNormalized')!==user.email!.toLowerCase() || !exerciseRevision(a!.get('lifecycleRevision')) ||
        w!.get('schemaVersion')!==1 || w!.get('status')!=='active' || m!.get('schemaVersion')!==1 || m!.get('workspaceId')!==wid || m!.get('userId')!==uid || m!.get('role')!=='client' || m!.get('status')!=='active')fail('unauthorized');
      const value=header(h!.data(),uid,wid,d.assignmentId),now=clock();if(!millis(now) || now<ownStarted || now-ownStarted>=5000 || value.accessExpiresAtMillis<=now)fail('unauthorized');
      return {workspaceId:wid,clientUid:uid,assignmentId:d.assignmentId,snapshotHash:value.snapshotHash,accessExpiresAtMillis:value.accessExpiresAtMillis,serverNowMillis:now};
    },{maxAttempts:3});
  }
  const mutation = d?.action === 'assign-program-version';
  const fields = mutation ? ['action','workspaceId','idempotencyKey','assignment'] : d?.action === 'assignment-prepare' ? ['action','workspaceId','clientUid','templateId','versionId','scheduledInstantMillis'] :
    d?.action === 'assignment-list' ? ['action','workspaceId','clientUid','after'] : d?.action === 'assignment-get' ? ['action','workspaceId','clientUid','assignmentId'] : d?.action === 'assignment-document' ? ['action','workspaceId','clientUid','assignmentId','relativePath'] : d?.action === 'assignment-preview-item' ? ['action','workspaceId','clientUid','assignment','itemId'] : [];
  if (!fields.length || !exact(d, fields) || bytes({ data: d }) > 32768) fail();
  const wid = participantUid(strictPathIdentifier(d.workspaceId,'workspace'),'workspace');
  if (mutation) assignmentDescriptor(d);
  const client = participantUid(mutation ? d.assignment.clientUid : d.clientUid,'client');
  if (uid === client) fail('unauthorized');
  if (d.action === 'assignment-prepare' && (!exerciseId(d.templateId) || !validVersionId(d.versionId) || !millis(d.scheduledInstantMillis)) ||
    ['assignment-get','assignment-document'].includes(d.action) && !exerciseId(d.assignmentId) || d.action === 'assignment-list' && d.after !== null &&
    (!exact(d.after, ['accessExpiresAtMillis','assignmentId']) || !millis(d.after.accessExpiresAtMillis) || !exerciseId(d.after.assignmentId))) fail();
  if (d.action === 'assignment-preview-item') {
    assignmentDescriptor({action:'assign-program-version',workspaceId:wid,idempotencyKey:'or1-b91f0176ec74475e8cf7406e25b7e9d3-preview',assignment:d.assignment});
    if(d.assignment.clientUid!==client || !exerciseId(d.itemId))fail();
  }
  if(d.action==='assignment-document' && (typeof d.relativePath!=='string' || d.relativePath.length>160))fail();
  const target = await verifiedEmulatorIdentity(client);
  await admission(db,uid,clock);
  const started = clock(), generatedAssignment = randomUUID(), generatedPlan = randomUUID();
  return db.runTransaction(async tx => {
    const deadline = () => { const now=clock(); if (!millis(now) || now < started || now-started >= 5000) fail(); }; deadline();
    if (mutation) await requireAssignmentCoverage(tx,db,d.idempotencyKey);
    const authority = await assignmentAuthority(tx,db,uid,client,wid,user.email!.toLowerCase(),target.email!.toLowerCase());
    const collection = db.collection(`users/${client}/workspaces/${wid}/assignedPrograms`);
    let result: Data;
    if (d.action === 'assignment-list') {
      let query = collection.where('trainerId','==',uid).where('accessStatus','==','active').where('accessExpiresAt','>',Timestamp.fromMillis(clock()))
        .orderBy('accessExpiresAt').orderBy(FieldPath.documentId()).limit(6);
      if (d.after !== null) query = query.startAfter(Timestamp.fromMillis(d.after.accessExpiresAtMillis),d.after.assignmentId);
      const rows = (await tx.get(query)).docs.map(s => header(s.data(),client,wid,s.id));
      if (rows.some(h => h.trainerId !== uid || h.accessExpiresAtMillis <= clock())) fail();
      result = { workspaceId: wid, clientUid: client, items: rows.slice(0,5), after: rows.length > 5 ? { accessExpiresAtMillis: rows[4]!.accessExpiresAtMillis, assignmentId: rows[4]!.assignmentId } : null };
    } else if (['assignment-get','assignment-document'].includes(d.action)) {
      const h = header((await tx.get(collection.doc(d.assignmentId))).data(),client,wid,d.assignmentId);
      if (h.trainerId !== uid || h.accessExpiresAtMillis <= clock()) fail('unauthorized');
      const manifestPath = `${collection.path}/${d.assignmentId}/manifests/download`, manifest = (await tx.get(db.doc(manifestPath))).data();
      if (!manifest || bytes(manifest)>8192 || !Array.isArray(manifest.requiredPaths) || manifest.requiredPaths.length < 4 || manifest.requiredPaths.length > 15 ||
        manifest.requiredPaths.some((p: unknown) => typeof p !== 'string' || !/^snapshots\/content(?:\/workouts\/[a-f0-9-]{36}(?:\/exercises\/[a-f0-9-]{36})?)?$|^plannedWorkouts\/[a-f0-9-]{36}$/.test(p))) fail();
      const base=collection.doc(d.assignmentId).path;
      const snapshots=await tx.getAll(...manifest!.requiredPaths.map((p:string)=>db.doc(`${base}/${p}`)));
      const docs=new Map<string,Data>([[base,h],[manifestPath,manifest!]]);
      for(const s of snapshots) { if(!s.exists || bytes(s.data())>24576)fail(); docs.set(s.ref.path,assignmentDocumentDto(s.data()!)); }
      if(!validateClientAssignmentDocuments(client,wid,d.assignmentId,docs) || h.accessExpiresAtMillis<=clock())fail();
      // Per-document packets preserve the generic transport cap. No aggregate snapshot response.
      if(d.action==='assignment-document') {
        const value=docs.get(d.relativePath===''?base:`${base}/${d.relativePath}`);
        if(!value)fail();
        result={workspaceId:wid,clientUid:client,assignmentId:d.assignmentId,relativePath:d.relativePath,document:value};
      } else result={workspaceId:wid,clientUid:client,header:h,inventory:manifest};
    } else {
      const a = mutation || d.action==='assignment-preview-item' ? d.assignment : null;
      if (mutation) {
        const b=assignmentBinding(uid,d),receipt=db.doc(`lifecycleCommands/${b.id}`);
        const [r,legacy]=await tx.getAll(receipt,db.doc(`assignmentCommands/${b.id}`));
        if (legacy!.exists) fail('outcome-unavailable');
        if(ASSIGNMENT_AUTHORITY.filter(k=>k!=='workspaceMembershipRevision').some(k=>authority[k]!==a.authority[k]))fail('unauthorized');
        if (r!.exists) {
          const v=r!.data()!;
          if(bytes(v)>4096 || !exact(v,['schemaVersion','commandKind','callerUid','workspaceId','requestHash','result','committedAt']) || v.schemaVersion!==1 ||
            v.commandKind!==ASSIGNMENT_KIND || v.callerUid!==uid || v.workspaceId!==wid || v.requestHash!==b.hash || !stamp(v.committedAt) || !validAssignmentDecision(v.result,d))fail('outcome-unavailable');
          deadline();return {...v.result,replayed:true};
        }
        // Replay needs current eligibility, but successful assignment advances this tuple itself.
        if(ASSIGNMENT_AUTHORITY.some(k=>authority[k]!==a.authority[k]))fail('unauthorized');
      }
      const tid=a?a.templateId:d.templateId,vid=a?a.versionId:d.versionId;
      // Intentionally no root/latest pointer or live exercise lookup. Version identity is immutable.
      const v=sourceVersion((await tx.get(db.doc(`workspaces/${wid}/programTemplates/${tid}/versions/${vid}`))).data(),uid,wid,tid,vid);
      const copy=assignmentCopy(v,uid,wid,tid);
      if(d.action==='assignment-preview-item') {
        if(ASSIGNMENT_AUTHORITY.some(k=>authority[k]!==a.authority[k]) || v.contentHash!==a.sourceContentHash || v.versionNumber!==a.versionNumber || assignmentCopyHash(copy)!==a.snapshotHash)fail();
        const item=copy.workouts[0].items.find((i:Data)=>i.id===d.itemId);if(!item)fail();
        result={workspaceId:wid,clientUid:client,sourceContentHash:v.contentHash,snapshotHash:a.snapshotHash,item};
      } else if (!mutation) {
        const now=clock();
        const prepared={assignmentId:generatedAssignment,plannedWorkoutId:generatedPlan,clientUid:client,templateId:tid,versionId:vid,versionNumber:v.versionNumber,
          sourceContentHash:v.contentHash,copyPolicyVersion:1,snapshotHash:assignmentCopyHash(copy),authority,preparedAtMillis:now,scheduledInstantMillis:d.scheduledInstantMillis,accessExpiresAtMillis:d.scheduledInstantMillis+DAY_MS};
        // Reuse frozen PR 1a descriptor validation, without reserving a command key or effect.
        assignmentDescriptor({action:'assign-program-version',workspaceId:wid,idempotencyKey:'or1-b91f0176ec74475e8cf7406e25b7e9d3-preview',assignment:prepared});
        result={workspaceId:wid,assignment:prepared,preview:{copyPolicyVersion:copy.copyPolicyVersion,title:copy.title,description:copy.description,
          workouts:copy.workouts.map((w:Data)=>({id:w.id,title:w.title,itemIds:w.items.map((i:Data)=>i.id)}))}};
      } else {
        let status: string=executionTime(a,clock());
        if(status==='current') status=v.versionNumber!==a.versionNumber || v.contentHash!==a.sourceContentHash?'assignment-source-conflict':assignmentCopyHash(copy)!==a.snapshotHash?'assignment-copy-conflict':'assignment-created';
        const docs=status==='assignment-created'?materializeAssignment(d,v,uid):new Map<string,Data>();
        // Two bounded occupancy probes; Firestore create preconditions also refuse every
        // orphan child atomically, without inventing a durable conflict from corrupt history.
        const occupied=docs.size?await tx.getAll(collection.doc(a.assignmentId),db.doc(`workspaces/${wid}/assignedPrograms/${a.assignmentId}`)):[];
        if(occupied.some(s=>s.exists)) {
          if(!occupied[0]!.exists || !occupied[1]!.exists)fail('assignment-integrity-unavailable');
          const h=header(occupied[0]!.data(),client,wid,a.assignmentId),index=occupied[1]!.data();
          if(h.trainerId!==uid || !exact(index,['schemaVersion','workspaceId','clientId','assignmentId','trainerId','status','revision']) ||
            bytes(index)>4096 || index.schemaVersion!==1 || index.workspaceId!==wid || index.clientId!==client || index.assignmentId!==a.assignmentId ||
            index.trainerId!==uid || index.status!=='active' || index.revision!==h.revision)fail('assignment-integrity-unavailable');
          status='assignment-id-conflict';
        }
        if(beforeWrites)await beforeWrites();deadline();
        // Scheduling is rechecked after an injected barrier as well as transaction retries.
        if(executionTime(a,clock())!=='current')status='assignment-schedule-conflict';
        const decision=assignmentDecision(d,status),binding=assignmentBinding(uid,d);
        if(status==='assignment-created') {
          for(const [path,value] of assignmentStoredDocuments(docs))tx.create(db.doc(path),value);
          tx.update(db.doc(`workspaces/${wid}`),{membershipRevision:authority.workspaceMembershipRevision+1});
        }
        tx.create(db.doc(`lifecycleCommands/${binding.id}`),{schemaVersion:1,commandKind:ASSIGNMENT_KIND,callerUid:uid,workspaceId:wid,requestHash:binding.hash,result:decision,committedAt:Timestamp.fromMillis(clock())});
        result={...decision,replayed:false};
      }
    }
    deadline(); if(bytes({result})>65536)fail('assignment-response-oversize'); return result;
  },{maxAttempts:3});
}
