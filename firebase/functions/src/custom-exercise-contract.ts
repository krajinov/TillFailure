import { participantUid, strictPathIdentifier } from "./input-validation.js";

type Data = Record<string, any>;
export const EXERCISE_AUTHORITY = ["authorLifecycleRevision", "authorMembershipRevision", "workspaceMembershipRevision"];
export const EXERCISE_MUSCLES = ["arms", "back", "chest", "core", "legs", "shoulders", "whole-body"];
export const EXERCISE_EQUIPMENT = ["band", "barbell", "bodyweight", "cable", "dumbbell", "machine"];
export const exerciseAction = (a: unknown) => a === "create-custom-exercise" || a === "correct-custom-exercise";
export const exerciseRevision = (v: unknown): v is number => Number.isSafeInteger(v) && (v as number) >= 1 && (v as number) <= 2147483646;
export const exerciseId = (v: unknown): v is string => typeof v === "string" && /^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/.test(v);
export const exerciseExact = (v: unknown, fields: string[]): v is Data => !!v && typeof v === "object" && !Array.isArray(v) && Object.keys(v).sort().join() === [...fields].sort().join();
export const exerciseBytes = (v: unknown) => Buffer.byteLength(JSON.stringify(v), "utf8");
export function exerciseText(v: unknown, max: number, multiline = false): v is string {
  if (typeof v !== "string" || !v.trim() || v.trim() !== v || [...v].length > max || Buffer.byteLength(v, "utf8") > max * 4) return false;
  return [...v].every(c => { const n = c.codePointAt(0)!; return !(n >= 0xd800 && n <= 0xdfff || n < 32 && !(multiline && n === 10) || n >= 127 && n <= 159); });
}
export function exerciseLabels(v: unknown, tokens: string[]): v is string[] {
  return Array.isArray(v) && v.length <= 3 && v.every((s, i) => tokens.includes(s) && (i === 0 || v[i - 1] < s));
}
export function exerciseDescription(v: Data): boolean {
  return exerciseText(v.name, 120) && exerciseText(v.instructions, 2000, true) && exerciseLabels(v.muscleGroups, EXERCISE_MUSCLES) &&
    exerciseLabels(v.equipment, EXERCISE_EQUIPMENT) && (!v.equipment.includes("bodyweight") || v.equipment.length === 1);
}
export function validExerciseWrite(v: unknown, action: unknown): v is Data {
  return exerciseAction(action) && exerciseExact(v, ["exerciseId", "expectedRevision", "authority", "name", "instructions", "muscleGroups", "equipment"]) &&
    exerciseId(v.exerciseId) && exerciseExact(v.authority, EXERCISE_AUTHORITY) && EXERCISE_AUTHORITY.every(k => exerciseRevision(v.authority[k])) &&
    (action === "create-custom-exercise" ? v.expectedRevision === 0 : exerciseRevision(v.expectedRevision) && v.expectedRevision < 2147483646) && exerciseDescription(v);
}
export function validExerciseDescriptor(d: unknown): d is Data {
  if (!exerciseExact(d, ["action", "workspaceId", "idempotencyKey", "exerciseWrite"]) || !validExerciseWrite(d.exerciseWrite, d.action)) return false;
  try { participantUid(strictPathIdentifier(d.workspaceId, "workspace"), "workspace"); participantUid(strictPathIdentifier(d.idempotencyKey, "key"), "key"); } catch { return false; }
  const resolver = { protocolVersion: 1, descriptor: d };
  return JSON.stringify(resolver).length <= 8192 && exerciseBytes(resolver) <= 24576 && exerciseBytes({ data: d }) <= 32768 && exerciseBytes({ data: resolver }) <= 32768;
}
export function exerciseDecisionValid(v: unknown, wid: string, p: Data, action: unknown): boolean {
  return exerciseExact(v, ["status", "workspaceId", "exerciseId", "exerciseRevision"]) && v.workspaceId === wid && v.exerciseId === p.exerciseId && exerciseRevision(v.exerciseRevision) &&
    (v.status === "exercise-conflict" ? v.exerciseRevision > p.expectedRevision : action === "create-custom-exercise" ? v.status === "exercise-created" && v.exerciseRevision === 1 : v.status === "exercise-corrected" && v.exerciseRevision === p.expectedRevision + 1);
}
