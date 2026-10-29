// Create-only synthetic picker fixtures for the isolated PR 3 native supervisor.
import { emulatorFirestore } from '../lib/src/environment.js';
import { customExerciseOperation } from '../lib/src/custom-exercises.js';
import { requireProvisioningEmulators } from '../lib/src/provisioning.js';
import { RESOLUTION_GENERATION } from '../lib/src/command-identity.js';
requireProvisioningEmulators();
const prefix=process.env.TF_M6P3_PREFIX;
if(!prefix || !/^m6p3r[0-9a-z]{6,24}$/.test(prefix))throw new Error('Require fresh UI prefix');
const db=emulatorFirestore();
for(let n=1;n<=21;n++)await db.doc(`systemExercises/${prefix}_${String(n).padStart(2,'0')}`).create({schemaVersion:1,name:`Synthetic movement ${n}`,nameNormalized:`synthetic movement ${n}`,muscleGroups:['legs'],equipment:['bodyweight'],instructions:'Original synthetic demonstration.\nTest the program picker.',status:'published'});
for(const platform of ['ios','android']) {
  const uid=`${prefix}_${platform}_ui`,workspaceId=`${uid}_ws`;
  const authority=(await customExerciseOperation(db,uid,{action:'exercise-prepare',workspaceId})).authority;
  await customExerciseOperation(db,uid,{action:'create-custom-exercise',workspaceId,idempotencyKey:`or1-${RESOLUTION_GENERATION}-${prefix}-${platform}-ui-source`,exerciseWrite:{exerciseId:'00000000-0000-4000-8000-000000000099',expectedRevision:0,authority,name:'Synthetic custom movement',instructions:'Original synthetic custom demonstration.',muscleGroups:['legs'],equipment:['bodyweight']}});
}
console.log('Created synthetic picker fixtures; no credentials or payloads logged.');
